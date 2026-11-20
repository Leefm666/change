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

Step 4 确认的每个场景都要落两套测试:

- **Mock 版**:mock 掉 Repository/DAO 依赖,断言 Step 3 详设文档里人工推导的预期值。验证的是"Java 实现是否忠实翻译了理解的逻辑"。
- **直连数据库版**:用真实 JDBC(`CallableStatement`)直接调用 .pck 里的存储过程,不 mock 任何数据库交互,拿到真实返回值/真实抛出的异常/真实的表状态变化。验证的是"人工理解是否等于数据库真实行为"。

两套测试断言同一批场景,但裁判不同——mock 版的裁判是人的理解,直连版的裁判是数据库本身。

### Step 6:直连数据库验证,收敛详设文档

跑 Step 5 的直连数据库版 UT。实际结果和 Step 3 文档预期不一致时,**回到 Step 3 修订文档**,把这个之前没想到的真实行为写进去,而不是直接改 Java 实现了事。重复 Step 3→6,直到直连数据库版 UT 全部通过且和文档预期一致。

## 阶段二:Java 替换与双轨切换

### Step 7:定义 Java 方法,落地全局变量处理结论

为包头里每个 public 成员定义一个 Java 方法签名,入参/出参对齐存储过程签名。全局变量按 Step 3 文档里的处理结论落地——如果结论是"需业务方确认",这里不擅自设计绕过方案,原样阻塞并告知用户,不能自己拍板。

### Step 8:UT 加特性开关断言

在 Step 5 的 UT 基础上,新增开关维度的断言:开关关闭时路由到旧存储过程网关,开启时路由到新 Java 实现,同一场景两条路径都要断言(结果、异常类型、SQLSTATE 一致)。

### Step 9:实现 Java 方法,接入特性开关

写 Service 方法(控制流照存储过程逐句翻译,不要顺手优化)+ MyBatis Mapper(参数化 SQL,不含业务逻辑)。特性开关的实现模式参考 [reference/feature-switch-pattern.md](reference/feature-switch-pattern.md)。

### Step 10:全量验证

Mock UT + 直连数据库 UT + 特性开关双路径断言,全部跑一遍,全绿才算这个存储过程迁移完成。任何一层不通过都要回到对应的前置 Step,不允许跳过失败继续往后做。
