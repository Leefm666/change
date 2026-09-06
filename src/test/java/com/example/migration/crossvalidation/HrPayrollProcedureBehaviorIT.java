package com.example.migration.crossvalidation;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * 只验证真实 openGauss 包 hr.hr_payroll.calculate_and_approve_payroll 本身的行为
 * (不涉及 Java 实现),对应
 * docs/rules/hr.hr_payroll.calculate_and_approve_payroll.testcases.md 里全部场景。
 * 种子数据直接写在本类的 @BeforeEach 里(不拆单独 .sql 文件),用 ON DUPLICATE KEY UPDATE
 * 做幂等重置——不依赖 JUnit 事务回滚,因为存储过程调用走的是独立 autocommit 连接,
 * 不在任何 Java 事务的回滚范围内(详见 docs/rules/demo.settle_order.md 的"幂等性"教训)。
 *
 * 这一层是"数据库当裁判"的第一手证据来源;第二个 IT 文件
 * PayrollJavaVsProcedureIT 负责比较 Java 实现是否复现了这里验证过的行为。
 *
 * 运行方式: OG_TEST_PASSWORD=<密码> mvn verify -P cross-validation
 */
@Tag("cross-validation")
class HrPayrollProcedureBehaviorIT {

    private static DataSource dataSource;
    private static JdbcProcedureExecutionRecorder procedureRecorder;

    @BeforeAll
    static void setUpDataSource() throws Exception {
        String password = PayrollTestFixtures.requirePassword();
        dataSource = PayrollTestFixtures.buildDataSource(password);
        procedureRecorder = PayrollTestFixtures.buildProcedureRecorder(dataSource);
    }

    @BeforeEach
    void resetData() throws SQLException {
        PayrollTestFixtures.resetAndSeed(dataSource);
    }

    @Test
    void success_basic_noDeductions() {
        ProcedureExecutionResult result = call(2, 202503, 10, null);

        assertThat(result.thrownError()).isEmpty();
        assertThat((BigDecimal) result.outParams().get("o_net_salary")).isEqualByComparingTo("9710.00");
        assertThat(result.outParams().get("o_status")).isEqualTo("APPROVED");
    }

    @Test
    void success_overrideZeroBonus() {
        ProcedureExecutionResult result = call(3, 202501, 10, BigDecimal.ZERO);

        assertThat(result.thrownError()).isEmpty();
        assertThat((BigDecimal) result.outParams().get("o_net_salary")).isEqualByComparingTo("11150.00");
    }

    @Test
    void success_naturalCRatingZeroBonus() {
        ProcedureExecutionResult result = call(3, 202501, 10, null);

        assertThat(result.thrownError()).isEmpty();
        assertThat((BigDecimal) result.outParams().get("o_net_salary")).isEqualByComparingTo("11150.00");
    }

    @Test
    void success_deductionWithDisputedSkip() {
        ProcedureExecutionResult result = call(1, 202501, 10, null);

        assertThat(result.thrownError()).isEmpty();
        assertThat((BigDecimal) result.outParams().get("o_net_salary")).isEqualByComparingTo("22990.00");
    }

    /** 隐藏行为1:员工不存在时,openGauss 自动抛 P0002,不是源码里手写的 P2001。 */
    @Test
    void failure_employeeNotFound_throwsP0002() {
        ProcedureExecutionResult result = call(999, 202501, 10, null);

        assertThat(result.thrownError()).isPresent();
        assertThat(result.thrownError().get().sqlState()).isEqualTo("P0002");
    }

    /** null 输入边界:p_employee_id 为 NULL 时,与员工不存在走同一条 P0002 路径。 */
    @Test
    void nullEmployeeId_throwsP0002() {
        ProcedureExecutionResult result = call(null, 202501, 10, null);

        assertThat(result.thrownError()).isPresent();
        assertThat(result.thrownError().get().sqlState()).isEqualTo("P0002");
    }

    @Test
    void failure_approverNotFound_throwsP0002() {
        ProcedureExecutionResult result = call(1, 202501, 999, null);

        assertThat(result.thrownError()).isPresent();
        assertThat(result.thrownError().get().sqlState()).isEqualTo("P0002");
    }

    @Test
    void failure_gradeMissingSalaryStandard_throwsP0002() throws SQLException {
        PayrollTestFixtures.upsertEmployee(dataSource, 5, "孙七", "P9", "B");

        ProcedureExecutionResult result = call(5, 202501, 10, null);

        assertThat(result.thrownError()).isPresent();
        assertThat(result.thrownError().get().sqlState()).isEqualTo("P0002");
    }

    /** 隐藏行为2:审批失败时,前面已写入的 PAYROLL_START 审计日志随事务一起回滚。 */
    @Test
    void failure_approvalOverLimit_throwsP1001AndRollsBackAuditLog() throws SQLException {
        ProcedureExecutionResult result = call(1, 202501, 11, null);

        assertThat(result.thrownError()).isPresent();
        assertThat(result.thrownError().get().sqlState()).isEqualTo("P1001");
        assertThat(PayrollTestFixtures.countAuditLog(dataSource)).isZero();
    }

    @Test
    void failure_netBeforeTaxNegative_throwsP1003() throws SQLException {
        PayrollTestFixtures.insertDeductionItem(dataSource, 3, 202502, "PENALTY", "999999.00", "NORMAL");

        ProcedureExecutionResult result = call(3, 202502, 10, BigDecimal.ZERO);

        assertThat(result.thrownError()).isPresent();
        assertThat(result.thrownError().get().sqlState()).isEqualTo("P1003");
    }

    @Test
    void taxBracketBoundary_upperInclusive() {
        ProcedureExecutionResult result = call(4, 202599, 10, new BigDecimal("2000.00"));

        assertThat(result.thrownError()).isEmpty();
        assertThat((BigDecimal) result.outParams().get("o_net_salary")).isEqualByComparingTo("4850.00");
    }

    @Test
    void taxBracketBoundary_overByOne() {
        ProcedureExecutionResult result = call(4, 202597, 10, new BigDecimal("2001.00"));

        assertThat(result.thrownError()).isEmpty();
        assertThat((BigDecimal) result.outParams().get("o_net_salary")).isEqualByComparingTo("4850.90");
    }

    /** E7:税率表为空时 tax=0,净额=税前净额全额发放,不抛异常(潜在业务bug候选,原样保留)。 */
    @Test
    void taxBracketTableEmpty_zeroTax() throws SQLException {
        PayrollTestFixtures.clearTaxBrackets(dataSource, 2025);

        ProcedureExecutionResult result = call(4, 202596, 10, new BigDecimal("1000.00"));

        assertThat(result.thrownError()).isEmpty();
        assertThat((BigDecimal) result.outParams().get("o_net_salary")).isEqualByComparingTo("4000.00");
    }

    @Test
    void repeatedCall_nonIdempotent_insertsTwoApprovalRows() throws SQLException {
        call(2, 202503, 10, null);
        call(2, 202503, 10, null);

        assertThat(PayrollTestFixtures.countApprovals(dataSource, 2, 202503)).isEqualTo(2);
    }

    private ProcedureExecutionResult call(Integer employeeId, int periodId, int approverId, BigDecimal override) {
        return procedureRecorder.invoke(
                "{call hr.hr_payroll.calculate_and_approve_payroll(?, ?, ?, ?, ?, ?)}",
                List.of(
                        employeeId == null
                                ? new InParam(1, null, Types.INTEGER)
                                : new InParam(1, employeeId, Types.INTEGER),
                        new InParam(2, periodId, Types.INTEGER),
                        new InParam(3, approverId, Types.INTEGER),
                        override == null ? new InParam(4, null, Types.NUMERIC) : new InParam(4, override, Types.NUMERIC)),
                List.of(
                        new OutParamSpec(5, "o_net_salary", Types.NUMERIC),
                        new OutParamSpec(6, "o_status", Types.VARCHAR)),
                List.of());
    }
}
