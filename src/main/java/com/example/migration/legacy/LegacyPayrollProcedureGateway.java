package com.example.migration.legacy;

import com.example.migration.domain.PayrollResult;
import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

/**
 * 生产代码里直接调用真实 openGauss 包 hr.hr_payroll 的入口,供灰度开关关闭/未命中
 * 灰度时使用。调用方式与 src/test/.../crossvalidation/PayrollCrossValidationFixtures 用的
 * JdbcProcedureExecutionRecorder 一致,那套调用方式已经在交叉验证测试里对着真实 openGauss
 * 跑通过,这里是同样的写法搬到 main 代码,不引入新的不确定性。
 *
 * 不包 @Transactional:存储过程内部自带 EXCEPTION 块处理回滚(见
 * docs/rules/hr.hr_payroll.calculate_and_approve_payroll.md 的"隐藏行为2"),
 * 这里保持每次调用走独立的 autocommit 连接,和交叉验证测试验证过的真实行为一致。
 */
@Component
public class LegacyPayrollProcedureGateway {

    private final DataSource dataSource;

    public LegacyPayrollProcedureGateway(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public PayrollResult call(int employeeId, int periodId, int approverId, BigDecimal bonusOverride) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (CallableStatement statement = connection.prepareCall(
                "{call hr.hr_payroll.calculate_and_approve_payroll(?, ?, ?, ?, ?, ?)}")) {
            statement.setInt(1, employeeId);
            statement.setInt(2, periodId);
            statement.setInt(3, approverId);
            if (bonusOverride == null) {
                statement.setNull(4, Types.NUMERIC);
            } else {
                statement.setBigDecimal(4, bonusOverride);
            }
            statement.registerOutParameter(5, Types.NUMERIC);
            statement.registerOutParameter(6, Types.VARCHAR);
            statement.execute();

            BigDecimal netSalary = statement.getBigDecimal(5);
            String status = statement.getString(6);
            return new PayrollResult(netSalary, status);
        } catch (SQLException e) {
            throw new LegacyPayrollProcedureFailureException(e.getSQLState(), e.getMessage(), e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
