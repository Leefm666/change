# 测试数据基线

存放 Testcontainers 启动 openGauss 后用于初始化的表结构 + 种子数据脚本。

每个交叉验证测试类在 `@BeforeEach` 重新加载这里的脚本(`TRUNCATE ... RESTART IDENTITY CASCADE` 后重新执行 INSERT,或用 Flyway `clean` + `migrate`),保证测试之间互不污染、可重复运行。

文件命名建议:`{schema}.{proc_name}.schema.sql`(表结构+约束+序列)、`{schema}.{proc_name}.seed.sql`(种子数据)。
