package com.example.migration.legacy;

import com.example.migration.domain.SettlementResult;
import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

/**
 * 生产代码里直接调用真实 openGauss 存储过程 demo.settle_order 的入口,供灰度开关关闭/未命中
 * 灰度时使用。调用方式(CallableStatement + OUT 参数)与
 * src/test/.../support/JdbcProcedureExecutionRecorder 一致,那套调用方式已经在交叉验证测试里
 * 对着真实 openGauss 跑通过,这里是同样的写法搬到 main 代码,不引入新的不确定性。
 *
 * 不包 @Transactional:存储过程内部自带 EXCEPTION 块处理回滚(见
 * docs/rules/demo.settle_order.md),这里保持每次调用走独立的 autocommit 连接,
 * 和交叉验证测试验证过的真实行为一致。
 */
@Component
public class LegacySettleOrderProcedureGateway {

    private final DataSource dataSource;

    public LegacySettleOrderProcedureGateway(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public SettlementResult call(int orderId) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (CallableStatement statement = connection.prepareCall("{call demo.settle_order(?, ?, ?)}")) {
            statement.setInt(1, orderId);
            statement.registerOutParameter(2, Types.NUMERIC);
            statement.registerOutParameter(3, Types.VARCHAR);
            statement.execute();

            BigDecimal finalAmount = statement.getBigDecimal(2);
            String status = statement.getString(3);
            return new SettlementResult(finalAmount, status);
        } catch (SQLException e) {
            throw new LegacyProcedureFailureException(e.getSQLState(), e.getMessage(), e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
