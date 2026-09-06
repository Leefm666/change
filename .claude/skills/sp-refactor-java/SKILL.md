---
name: sp-refactor-java
description: 将 openGauss/PostgreSQL PL/pgSQL 存储过程(package 中定义在 .pck 包文件里的 procedure/function)迁移为 Java 代码,先建 UT 保护网再替换,内置特性开关双轨切换。仅适用于目标项目是 Spring+MyBatis、且能直连数据库跑真实 UT 的场景。
disable-model-invocation: true
argument-hint: ".pck 源文件路径 + 要迁移的 procedure/function 名字"
---

# sp-refactor-java

把一个 openGauss/PostgreSQL PL/pgSQL 存储过程,从 .pck 包文件迁移成 Java 代码(Spring+MyBatis 项目)。核心原则:**先用 UT 建立行为保护网,再用 Java 替换,用特性开关双轨切换**——任何一步验证不过,不允许跳过继续往后做。

## Step 0:适用边界自检

逐条检查,任意一条不满足就停止并告知用户原因,不要继续往下走:
- 源存储过程是 openGauss/PostgreSQL 的 PL/pgSQL,不是 MySQL/Oracle PL-SQL 等其他方言
- 存储过程定义在 .pck 包文件里(`CREATE PACKAGE` / `CREATE PACKAGE BODY`),不是裸的 `CREATE FUNCTION`/`CREATE PROCEDURE`
- 目标 Java 项目确实用 Spring+MyBatis(查 pom.xml/build.gradle 有没有 mybatis-spring 依赖)
- 能拿到直连数据库的连接信息——UT 要求真实 JDBC 执行,不接受用 mock 掉数据库这一层来"验证"

## 阶段一:建立 UT 保护网

### Step 1:提取包头与包体,识别全局变量

读取 .pck 文件,拆出两部分:
- **包头(spec)**:声明了哪些 procedure/function 是 public。只有这里声明的成员,阶段二才需要对应一个 Java 方法;包体里 private 的成员只做内部逻辑分析,不单独出 Java 方法。
- **包全局变量**:声明在包头或包体顶层、跨多个 procedure/function 共享的变量(不是某个 procedure 内部的局部变量)。逐个记录类型、初始值、哪些成员读它、哪些成员写它、生命周期是否跨越多次调用。这类变量在 Java 方法调用模型里没有直接对应物,处理方式的判断树见 [reference/global-state-mapping.md](reference/global-state-mapping.md),结论写进 Step 3 的详设文档。

### Step 2:盘点依赖

列出要迁移的 procedure/function 依赖的表、触发器、序列、调用的其他 procedure/function(包内或包外)。openGauss 存储过程体是不透明文本,系统表查不到函数体内的表依赖,直接读 .pck 源码文本找 `FROM`/`JOIN`/`UPDATE`/`INSERT INTO`/`CALL` 即可。

### Step 3:生成详细设计文档 → 暂停

用 [reference/design-doc-template.md](reference/design-doc-template.md) 的结构,基于 Step 1/2 的盘点结果逐条填写:签名、每个分支的业务含义、包全局变量的处理结论、边界条件(null 输入/空结果集/幂等性/隐式类型转换)、依赖清单、待业务方确认的事项。

完成后**停下来,把文档展示给用户,问是否需要补充或修改**。收到用户明确的"继续"或补充意见并处理完之前,不进入 Step 4。

### Step 4:设计测试用例 → 暂停

基于 Step 3 的详设文档,列出测试场景清单(自然语言描述,不是代码):覆盖文档里每一个分支、每一条边界条件,以及全局变量在多次调用间的读写时序场景。格式参考 [reference/test-case-template.md](reference/test-case-template.md)。

完成后**停下来,把用例清单展示给用户,问是否需要补充**。收到用户明确的"继续"或补充意见并处理完之前,不进入 Step 5。

### Step 5:生成 UT 代码

两个层次,职责不重叠,不要合并也不要拆得更碎:

- **Mock 单元测试**:mock 掉 Repository/DAO 依赖,断言 Step 3 详设文档里人工推导的预期值。验证的是"Java 实现是否忠实翻译了理解的逻辑",不连数据库,秒级跑完。Service/Routing/Controller 每层各自一个测试类。
- **交叉验证 IT(恰好两个文件,不多不少)**:
  1. **存储过程行为验证 IT**:只用真实 JDBC(`CallableStatement`)直接调用 .pck 里的存储过程,不涉及任何 Java 实现代码,断言 Step 4 用例清单里的每个场景。这是"数据库当裁判"的第一手证据来源。
  2. **Java 对比存储过程 IT**:同一批输入先调真实存储过程拿基准结果,再调 Java 实现比较是否一致;并且加一段"过特性开关"的断言——分别把开关打到旧路径和新路径,验证两条路径对同一输入的结果一致。

两个 IT 共用同一个 Fixtures 类(建 DataSource、种子数据、重置逻辑),不要为每个场景单独建各自的种子数据。

**种子数据直接写在 Fixtures 类的 Java 代码里,不要拆成单独的 `.sql` 文件**——除非同一份种子数据会被三个以上的地方复用,否则拆文件只是多一层"改测试要记得同步改 SQL"的维护成本,没有对应的收益。

**重置机制不能依赖 JUnit/Spring 的事务回滚**:调用真实存储过程的连接是独立的 autocommit 连接,不在任何 Java 测试事务的回滚范围内;如果指望"测试方法包一层事务,最后自动回滚"来清理数据,回滚只会清掉 Java 侧写入,清不掉存储过程侧的写入,两边状态会不对称,愈跑愈脏。正确做法:
- 不会被外键依赖、只是维度字典的表(员工/审批人/税率标准这类),用 `INSERT ... ON DUPLICATE KEY UPDATE` 幂等覆写,不依赖任何"先清空再插入"的执行顺序,测试异常中断也能自我修复。
- 会被反复写入的业务/流水表(审批记录/审计日志/流水),用 `TRUNCATE` 清空。

### Step 6:直连数据库验证,收敛详设文档

跑 Step 5 的两个 IT。实际结果和 Step 3 文档预期不一致时,**回到 Step 3 修订文档**,把这个之前没想到的真实行为写进去,而不是直接改 Java 实现了事。重复 Step 3→6,直到两个 IT 全部通过且和文档预期一致。

**文档里出现的每一个数值断言,必须是真实跑数据库拿到的输出,不能是手算/推算/凑整数**——凑出来的数字如果恰好和真实值不一致,会把一个本该在这一步暴露的错误,原样带进 Java 实现里,直到交叉验证阶段才会被发现,等于白走了一趟"文档先行"的流程。

## 阶段二:Java 替换与双轨切换

### Step 7:定义 Java 方法,落地全局变量处理结论

为包头里每个 public 成员定义一个 Java 方法签名,入参/出参对齐存储过程签名。全局变量按 Step 3 文档里的处理结论落地——如果结论是"需业务方确认",这里不擅自设计绕过方案,原样阻塞并告知用户,不能自己拍板。

### Step 8:UT 加特性开关断言

在 Step 5 的"Java 对比存储过程 IT"里补上过特性开关的断言(而不是新开一个测试文件):开关关闭时路由到旧存储过程网关,开启时路由到新 Java 实现,同一场景两条路径都要断言(结果、异常类型、SQLSTATE 一致)。Mock 层同样加一个 RoutingService 的单元测试类,验证开关百分比/哈希稳定性等纯逻辑,不需要连数据库。

### Step 9:实现 Java 方法,接入特性开关

写 Service 方法(控制流照存储过程逐句翻译,不要顺手优化)+ MyBatis Mapper(参数化 SQL,不含业务逻辑)。特性开关的实现模式参考 [reference/feature-switch-pattern.md](reference/feature-switch-pattern.md)。

### Step 10:全量验证

Mock 单元测试 + 两个交叉验证 IT(含特性开关双路径断言),全部跑一遍,全绿才算这个存储过程迁移完成。任何一层不通过都要回到对应的前置 Step,不允许跳过失败继续往后做。
