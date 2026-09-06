package com.example.migration.crossvalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.migration.domain.PayrollResult;
import com.example.migration.legacy.LegacyPayrollProcedureGateway;
import com.example.migration.routing.PayrollMigrationSwitchProperties;
import com.example.migration.routing.PayrollOutcome;
import com.example.migration.routing.PayrollOutcome.RouteTarget;
import com.example.migration.routing.PayrollRoutingService;
import com.example.migration.service.PayrollService;
import com.example.migration.support.InParam;
import com.example.migration.support.JdbcProcedureExecutionRecorder;
import com.example.migration.support.OutParamSpec;
import com.example.migration.support.ProcedureExecutionResult;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 先真实调用 hr.hr_payroll 存储过程拿到基准结果,再用同一批输入调用 Java 实现
 * (PayrollService)比较两者是否一致,以及通过灰度路由(PayrollRoutingService)时
 * 两条路径的结果是否一致。种子数据复用 PayrollTestFixtures(与
 * HrPayrollProcedureBehaviorIT 共用同一份基线)。
 *
 * 运行方式: OG_TEST_PASSWORD=<密码> mvn verify -P cross-validation
 */
@Tag("cross-validation")
class PayrollJavaVsProcedureIT {

    private static DataSource dataSource;
    private static PayrollService javaService;
    private static LegacyPayrollProcedureGateway legacyGateway;
    private static TransactionTemplate transactionTemplate;
    private static JdbcProcedureExecutionRecorder procedureRecorder;

    @BeforeAll
    static void setUpDataSource() throws Exception {
        String password = PayrollTestFixtures.requirePassword();
        dataSource = PayrollTestFixtures.buildDataSource(password);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        procedureRecorder = PayrollTestFixtures.buildProcedureRecorder(dataSource);
        javaService = PayrollTestFixtures.buildJavaService(dataSource);
        legacyGateway = new LegacyPayrollProcedureGateway(dataSource);
    }

    @BeforeEach
    void resetData() throws SQLException {
        PayrollTestFixtures.resetAndSeed(dataSource);
    }

    @Test
    void success_basic_javaMatchesProcedure() {
        ProcedureExecutionResult procedureResult = callProcedure(2, 202503, 10, null);
        resetDataQuietly();
        PayrollResult javaResult = callJavaInTransaction(2, 202503, 10, null);

        assertThat(procedureResult.thrownError()).isEmpty();
        assertThat(javaResult.netSalary())
                .isEqualByComparingTo((BigDecimal) procedureResult.outParams().get("o_net_salary"));
        assertThat(javaResult.netSalary()).isEqualByComparingTo("9710.00");
    }

    @Test
    void success_deductionWithDisputedSkip_javaMatchesProcedure() {
        ProcedureExecutionResult procedureResult = callProcedure(1, 202501, 10, null);
        resetDataQuietly();
        PayrollResult javaResult = callJavaInTransaction(1, 202501, 10, null);

        assertThat(procedureResult.thrownError()).isEmpty();
        assertThat(javaResult.netSalary())
                .isEqualByComparingTo((BigDecimal) procedureResult.outParams().get("o_net_salary"));
        assertThat(javaResult.netSalary()).isEqualByComparingTo("22990.00");
    }

    @Test
    void taxBracketBoundary_upperInclusive_javaMatchesProcedure() {
        ProcedureExecutionResult procedureResult = callProcedure(4, 202599, 10, new BigDecimal("2000.00"));
        resetDataQuietly();
        PayrollResult javaResult = callJavaInTransaction(4, 202599, 10, new BigDecimal("2000.00"));

        assertThat(procedureResult.thrownError()).isEmpty();
        assertThat(javaResult.netSalary())
                .isEqualByComparingTo((BigDecimal) procedureResult.outParams().get("o_net_salary"));
        assertThat(javaResult.netSalary()).isEqualByComparingTo("4850.00");
    }

    /** E7:税率表为空时,Java 实现和存储过程应该一致地返回 tax=0,不抛异常。 */
    @Test
    void taxBracketTableEmpty_javaMatchesProcedure() throws SQLException {
        PayrollTestFixtures.clearTaxBrackets(dataSource, 2025);
        ProcedureExecutionResult procedureResult = callProcedure(4, 202596, 10, new BigDecimal("1000.00"));

        resetDataQuietly();
        PayrollTestFixtures.clearTaxBrackets(dataSource, 2025);
        PayrollResult javaResult = callJavaInTransaction(4, 202596, 10, new BigDecimal("1000.00"));

        assertThat(procedureResult.thrownError()).isEmpty();
        assertThat(javaResult.netSalary())
                .isEqualByComparingTo((BigDecimal) procedureResult.outParams().get("o_net_salary"));
        assertThat(javaResult.netSalary()).isEqualByComparingTo("4000.00");
    }

    /** 隐藏行为1:员工不存在时两边都是 P0002,不是源码里的 P2001。 */
    @Test
    void failure_employeeNotFound_javaMatchesProcedure() {
        ProcedureExecutionResult procedureResult = callProcedure(999, 202501, 10, null);
        assertThat(procedureResult.thrownError()).isPresent();
        assertThat(procedureResult.thrownError().get().sqlState()).isEqualTo("P0002");

        resetDataQuietly();

        assertThatThrownBy(() -> callJavaInTransaction(999, 202501, 10, null))
                .hasMessageContaining("员工不存在: 999");
    }

    /** 隐藏行为1 的 P9 变体:等级缺薪资标准时两边都是 P0002,不是源码里的 P2002。 */
    @Test
    void failure_gradeMissingSalaryStandard_javaMatchesProcedure() throws SQLException {
        PayrollTestFixtures.upsertEmployee(dataSource, 5, "孙七", "P9", "B");

        ProcedureExecutionResult procedureResult = callProcedure(5, 202501, 10, null);
        assertThat(procedureResult.thrownError()).isPresent();
        assertThat(procedureResult.thrownError().get().sqlState()).isEqualTo("P0002");

        resetDataQuietly();
        PayrollTestFixtures.upsertEmployee(dataSource, 5, "孙七", "P9", "B");

        assertThatThrownBy(() -> callJavaInTransaction(5, 202501, 10, null))
                .hasMessageContaining("缺少等级 P9 的薪资标准");
    }

    /** 隐藏行为2:审批失败时,两边的 PAYROLL_START 审计日志都被回滚,不留残留。 */
    @Test
    void failure_approvalOverLimit_javaMatchesProcedureAndBothRollBackAuditLog() throws SQLException {
        ProcedureExecutionResult procedureResult = callProcedure(1, 202501, 11, null);
        assertThat(procedureResult.thrownError()).isPresent();
        assertThat(procedureResult.thrownError().get().sqlState()).isEqualTo("P1001");
        assertThat(PayrollTestFixtures.countAuditLog(dataSource)).isZero();

        resetDataQuietly();

        assertThatThrownBy(() -> callJavaInTransaction(1, 202501, 11, null))
                .hasMessageContaining("超过审批人");
        assertThat(PayrollTestFixtures.countAuditLog(dataSource)).isZero();
    }

    @Test
    void failure_netBeforeTaxNegative_javaMatchesProcedure() throws SQLException {
        PayrollTestFixtures.insertDeductionItem(dataSource, 3, 202502, "PENALTY", "999999.00", "NORMAL");

        ProcedureExecutionResult procedureResult = callProcedure(3, 202502, 10, BigDecimal.ZERO);
        assertThat(procedureResult.thrownError()).isPresent();
        assertThat(procedureResult.thrownError().get().sqlState()).isEqualTo("P1003");

        resetDataQuietly();
        PayrollTestFixtures.insertDeductionItem(dataSource, 3, 202502, "PENALTY", "999999.00", "NORMAL");

        assertThatThrownBy(() -> callJavaInTransaction(3, 202502, 10, BigDecimal.ZERO))
                .hasMessageContaining("数据异常");
    }

    /**
     * 特性开关双路径断言:同一批输入,开关走 LEGACY 时调真实存储过程,走 JAVA 时调 Java 实现,
     * 两条路径的结果应该一致——这是"双轨切换"真正要保证的契约,不是分别测两边就够了。
     */
    @Test
    void routingService_legacyPath_matchesJavaPath_onSuccess() {
        PayrollMigrationSwitchProperties legacyProps = new PayrollMigrationSwitchProperties();
        legacyProps.setEnabled(false);
        PayrollRoutingService legacyRouting = new PayrollRoutingService(legacyProps, javaService, legacyGateway);

        PayrollOutcome legacyOutcome = legacyRouting.calculateAndApprovePayroll(2, 202503, 10, null);
        assertThat(legacyOutcome.routedTo()).isEqualTo(RouteTarget.LEGACY_PROCEDURE);

        resetDataQuietly();

        PayrollMigrationSwitchProperties javaProps = new PayrollMigrationSwitchProperties();
        javaProps.setEnabled(true);
        javaProps.setRolloutPercentage(100);
        PayrollRoutingService javaRouting = new PayrollRoutingService(javaProps, javaService, legacyGateway);

        PayrollOutcome javaOutcome = transactionTemplate.execute(
                status -> javaRouting.calculateAndApprovePayroll(2, 202503, 10, null));
        assertThat(javaOutcome.routedTo()).isEqualTo(RouteTarget.JAVA);

        assertThat(javaOutcome.result().netSalary()).isEqualByComparingTo(legacyOutcome.result().netSalary());
        assertThat(javaOutcome.result().netSalary()).isEqualByComparingTo("9710.00");
    }

    @Test
    void routingService_legacyPath_matchesJavaPath_onApprovalLimitExceeded() {
        PayrollMigrationSwitchProperties legacyProps = new PayrollMigrationSwitchProperties();
        legacyProps.setEnabled(false);
        PayrollRoutingService legacyRouting = new PayrollRoutingService(legacyProps, javaService, legacyGateway);

        assertThatThrownBy(() -> legacyRouting.calculateAndApprovePayroll(1, 202501, 11, null))
                .hasMessageContaining("超过审批人");

        resetDataQuietly();

        PayrollMigrationSwitchProperties javaProps = new PayrollMigrationSwitchProperties();
        javaProps.setEnabled(true);
        javaProps.setRolloutPercentage(100);
        PayrollRoutingService javaRouting = new PayrollRoutingService(javaProps, javaService, legacyGateway);

        assertThatThrownBy(() -> transactionTemplate.execute(
                status -> javaRouting.calculateAndApprovePayroll(1, 202501, 11, null)))
                .hasMessageContaining("超过审批人");
    }

    private PayrollResult callJavaInTransaction(int employeeId, int periodId, int approverId, BigDecimal override) {
        return transactionTemplate.execute(status ->
                javaService.calculateAndApprovePayroll(employeeId, periodId, approverId, override));
    }

    private void resetDataQuietly() {
        try {
            PayrollTestFixtures.resetAndSeed(dataSource);
        } catch (SQLException e) {
            throw new IllegalStateException("重置测试数据失败", e);
        }
    }

    private ProcedureExecutionResult callProcedure(
            int employeeId, int periodId, int approverId, BigDecimal override) {
        return procedureRecorder.invoke(
                "{call hr.hr_payroll.calculate_and_approve_payroll(?, ?, ?, ?, ?, ?)}",
                List.of(
                        new InParam(1, employeeId, Types.INTEGER),
                        new InParam(2, periodId, Types.INTEGER),
                        new InParam(3, approverId, Types.INTEGER),
                        override == null ? new InParam(4, null, Types.NUMERIC) : new InParam(4, override, Types.NUMERIC)),
                List.of(
                        new OutParamSpec(5, "o_net_salary", Types.NUMERIC),
                        new OutParamSpec(6, "o_status", Types.VARCHAR)),
                List.of());
    }
}
