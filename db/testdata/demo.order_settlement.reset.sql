-- !! 仅供 Testcontainers / 一次性 docker-compose demo 容器使用 !!
-- 只清空 demo schema 下的表(该 schema 专为本次存储过程迁移验证创建,不会与任何真实业务 schema 同名)。
-- 严禁在生产库或任何共享的真实数据库连接上执行本脚本。
-- 前置校验:若当前库不存在 demo schema 或缺少预期表,直接报错退出,不做任何 TRUNCATE。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'demo' AND table_name = 'settlement_log'
    ) THEN
        RAISE EXCEPTION '安全检查失败: 当前数据库不存在 demo.settlement_log,拒绝执行清空操作(可能连接了错误的实例)';
    END IF;
END $$;

-- 注意:这个 openGauss 实例的运行模式不支持 TRUNCATE ... RESTART IDENTITY 或 ALTER SEQUENCE
-- (均报 "not yet supported"),只做 TRUNCATE。settlement_log.log_id 序列值会持续增长,
-- 不重置——测试断言只看 order_id/event/detail,不依赖 log_id 的具体取值,不影响正确性。
TRUNCATE TABLE demo.settlement_log CASCADE;
TRUNCATE TABLE demo.order_items CASCADE;
TRUNCATE TABLE demo.orders CASCADE;
TRUNCATE TABLE demo.inventory CASCADE;
TRUNCATE TABLE demo.customers CASCADE;
