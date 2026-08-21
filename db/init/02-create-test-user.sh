#!/usr/bin/env bash
# openGauss 官方镜像的 entrypoint 只在数据目录首次初始化时执行
# /docker-entrypoint-initdb.d/ 下的脚本(见 db/legacy-procedures/README.md 的
# 01-demo-schema-and-procedure.sql 挂载点),.sh 脚本会在完整的容器环境变量下运行,
# 因此可以直接读到 entrypoint 导出的 $GS_PASSWORD,不需要额外传密码。
#
# 这里创建的 migration_test 用户专门给交叉验证测试(OrderSettlementCrossValidationIT /
# OrderSettlementBatchRegressionIT)用:openGauss 禁止初始超级用户通过端口映射产生的
# "远程连接"登录,必须用非初始用户连接,见 docs/rules/demo.settle_order.md。
# 密码复用 GS_PASSWORD,保持单一密码来源,测试侧用同一个值设置 OG_TEST_PASSWORD。
set -Eeo pipefail

export LD_LIBRARY_PATH="/usr/local/opengauss/lib:${LD_LIBRARY_PATH:-}"

# 沿用官方 entrypoint.sh 里 docker_process_sql() 的调用方式(--username/--password 显式传参),
# 不依赖 PGPASSWORD 环境变量是否残留。
gsql -v ON_ERROR_STOP=1 --username "$GS_USER" --password "$GS_PASSWORD" --dbname postgres -c "
CREATE USER migration_test WITH PASSWORD '${GS_PASSWORD}' SYSADMIN;
GRANT ALL ON SCHEMA demo TO migration_test;
GRANT ALL ON ALL TABLES IN SCHEMA demo TO migration_test;
GRANT ALL ON ALL SEQUENCES IN SCHEMA demo TO migration_test;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA demo TO migration_test;
"
