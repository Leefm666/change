package com.example.migration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.migration.domain.Approver;
import com.example.migration.domain.DeductionItem;
import com.example.migration.domain.Employee;
import com.example.migration.domain.PayrollResult;
import com.example.migration.domain.TaxBracket;
import com.example.migration.exception.ApprovalLimitExceededException;
import com.example.migration.exception.NegativeNetSalaryException;
import com.example.migration.exception.PayrollEntityNotFoundException;
import com.example.migration.repository.ApproverRepository;
import com.example.migration.repository.DeductionItemRepository;
import com.example.migration.repository.EmployeeRepository;
import com.example.migration.repository.PayrollApprovalRepository;
import com.example.migration.repository.PayrollAuditLogRepository;
import com.example.migration.repository.SalaryStandardRepository;
import com.example.migration.repository.TaxBracketRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 断言值来自人工阅读 db/legacy-procedures/hr.hr_payroll.sql 推导的预期结果,
 * 并已通过 docs/rules/hr.hr_payroll.calculate_and_approve_payroll.md 记录的
 * 真实 openGauss 交叉验证确认一致。场景名对应
 * docs/rules/hr.hr_payroll.calculate_and_approve_payroll.testcases.md。
 */
@ExtendWith(MockitoExtension.class)
class PayrollServiceTest {

    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private ApproverRepository approverRepository;
    @Mock
    private SalaryStandardRepository salaryStandardRepository;
    @Mock
    private TaxBracketRepository taxBracketRepository;
    @Mock
    private DeductionItemRepository deductionItemRepository;
    @Mock
    private PayrollApprovalRepository payrollApprovalRepository;
    @Mock
    private PayrollAuditLogRepository payrollAuditLogRepository;

    private PayrollService service;

    private static final int FISCAL_YEAR = 2025;
    private static final Approver APPROVER_A = new Approver(10, "审批人A", new BigDecimal("100000.00"));
    private static final Approver APPROVER_B_LOW_LIMIT = new Approver(11, "审批人B", new BigDecimal("5000.00"));

    @BeforeEach
    void setUp() {
        service = new PayrollService(
                employeeRepository, approverRepository, salaryStandardRepository,
                taxBracketRepository, deductionItemRepository,
                payrollApprovalRepository, payrollAuditLogRepository);
        // lenient: 部分测试在到达计税逻辑前就已抛异常返回,不会用到这个 stub。
        org.mockito.Mockito.lenient().when(taxBracketRepository.findByFiscalYear(FISCAL_YEAR)).thenReturn(List.of(
                new TaxBracket(1, new BigDecimal("5000.00"), new BigDecimal("0.03")),
                new TaxBracket(2, new BigDecimal("20000.00"), new BigDecimal("0.10")),
                new TaxBracket(3, null, new BigDecimal("0.20"))));
    }

    @Test
    void success_basic_no_deductions() {
        when(approverRepository.findById(10)).thenReturn(Optional.of(APPROVER_A));
        when(employeeRepository.findById(2)).thenReturn(Optional.of(new Employee(2, "李四", "P3", "A")));
        when(salaryStandardRepository.findBaseSalary(FISCAL_YEAR, "P3")).thenReturn(Optional.of(new BigDecimal("8000.00")));
        when(deductionItemRepository.findByEmployeeAndPeriod(2, 202503)).thenReturn(List.of());

        PayrollResult result = service.calculateAndApprovePayroll(2, 202503, 10, null);

        // gross = 8000 + 8000*0.30 = 10400; tax = 150 + 10.10*100... 详细算法见 calc_progressive_tax:
        // 5000*0.03=150, 剩余5400*0.10=540, tax=690; net=10400-690=9710
        assertThat(result.netSalary()).isEqualByComparingTo("9710.00");
        assertThat(result.status()).isEqualTo("APPROVED");
        verify(payrollApprovalRepository).insert(2, 202503, 10,
                new BigDecimal("10400.00"), BigDecimal.ZERO, new BigDecimal("690.00"),
                new BigDecimal("9710.00"), "APPROVED");
    }

    @Test
    void success_override_zero_bonus() {
        when(approverRepository.findById(10)).thenReturn(Optional.of(APPROVER_A));
        when(employeeRepository.findById(3)).thenReturn(Optional.of(new Employee(3, "王五", "P4", "C")));
        when(salaryStandardRepository.findBaseSalary(FISCAL_YEAR, "P4")).thenReturn(Optional.of(new BigDecimal("12000.00")));
        when(deductionItemRepository.findByEmployeeAndPeriod(3, 202501)).thenReturn(List.of());

        PayrollResult result = service.calculateAndApprovePayroll(3, 202501, 10, BigDecimal.ZERO);

        assertThat(result.status()).isEqualTo("APPROVED");
        verify(payrollApprovalRepository).insert(eq(3), eq(202501), eq(10),
                eq(new BigDecimal("12000.00")), any(), any(), any(), eq("APPROVED"));
        // override 路径不应该再触发第二次 calc_base_salary 调用(不需要绩效等级)
        verify(employeeRepository, times(1)).findById(3);
    }

    @Test
    void success_natural_c_rating_zero_bonus() {
        when(approverRepository.findById(10)).thenReturn(Optional.of(APPROVER_A));
        when(employeeRepository.findById(3)).thenReturn(Optional.of(new Employee(3, "王五", "P4", "C")));
        when(salaryStandardRepository.findBaseSalary(FISCAL_YEAR, "P4")).thenReturn(Optional.of(new BigDecimal("12000.00")));
        when(deductionItemRepository.findByEmployeeAndPeriod(3, 202501)).thenReturn(List.of());

        PayrollResult result = service.calculateAndApprovePayroll(3, 202501, 10, null);

        assertThat(result.status()).isEqualTo("APPROVED");
        verify(payrollApprovalRepository).insert(eq(3), eq(202501), eq(10),
                eq(new BigDecimal("12000.00")), any(), any(), any(), eq("APPROVED"));
        // 未走 override:主流程查一次员工(算gross的calc_base_salary入口)+ calc_bonus 自己查一次员工(取perfRating)
        // + calc_bonus 内部又调用一次 calc_base_salary 重复查询,employeeRepository 共被调用3次(复刻源码重复查询)。
        verify(employeeRepository, times(3)).findById(3);
    }

    @Test
    void success_deduction_with_disputed_skip() {
        when(approverRepository.findById(10)).thenReturn(Optional.of(APPROVER_A));
        when(employeeRepository.findById(1)).thenReturn(Optional.of(new Employee(1, "张三", "P5", "S")));
        when(salaryStandardRepository.findBaseSalary(FISCAL_YEAR, "P5")).thenReturn(Optional.of(new BigDecimal("18000.00")));
        when(deductionItemRepository.findByEmployeeAndPeriod(1, 202501)).thenReturn(List.of(
                new DeductionItem(1, "SOCIAL_INSURANCE", new BigDecimal("800.00"), "NORMAL"),
                new DeductionItem(2, "HOUSING_FUND", new BigDecimal("400.00"), "NORMAL"),
                new DeductionItem(3, "PENALTY", new BigDecimal("200.00"), "DISPUTED")));

        PayrollResult result = service.calculateAndApprovePayroll(1, 202501, 10, null);

        assertThat(result.netSalary()).isEqualByComparingTo("22990.00");
        verify(payrollAuditLogRepository).insert("审批人A", "DEDUCTION_SKIPPED", "跳过争议中的扣款项 deduction_id=3");
        verify(payrollApprovalRepository).insert(1, 202501, 10,
                new BigDecimal("27000.00"), new BigDecimal("1200.00"), new BigDecimal("2810.00"),
                new BigDecimal("22990.00"), "APPROVED");
    }

    @Test
    void failure_employee_not_found() {
        when(approverRepository.findById(10)).thenReturn(Optional.of(APPROVER_A));
        when(employeeRepository.findById(999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.calculateAndApprovePayroll(999, 202501, 10, null))
                .isInstanceOf(PayrollEntityNotFoundException.class)
                .hasMessage("员工不存在: 999");

        verify(payrollApprovalRepository, never()).insert(anyInt(), anyInt(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void failure_employee_not_found_audit_log_rollback() {
        // mock 层只验证"审批日志确实被调用过(insert 被触发)",回滚效果本身依赖真实事务,
        // 由 Step 6 直连数据库版 UT 验证,mock 版不能替代对回滚机制的验证。
        when(approverRepository.findById(10)).thenReturn(Optional.of(APPROVER_A));
        when(employeeRepository.findById(999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.calculateAndApprovePayroll(999, 202501, 10, null))
                .isInstanceOf(PayrollEntityNotFoundException.class);

        verify(payrollAuditLogRepository).insert("审批人A", "PAYROLL_START",
                "开始核算 employee_id=999 period_id=202501");
    }

    @Test
    void failure_approver_not_found() {
        when(approverRepository.findById(999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.calculateAndApprovePayroll(1, 202501, 999, null))
                .isInstanceOf(PayrollEntityNotFoundException.class)
                .hasMessage("审批人不存在: 999");

        verify(payrollAuditLogRepository, never()).insert(any(), any(), any());
        verify(employeeRepository, never()).findById(anyInt());
    }

    @Test
    void failure_grade_missing_salary_standard() {
        when(approverRepository.findById(10)).thenReturn(Optional.of(APPROVER_A));
        when(employeeRepository.findById(5)).thenReturn(Optional.of(new Employee(5, "孙七", "P9", "B")));
        when(salaryStandardRepository.findBaseSalary(FISCAL_YEAR, "P9")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.calculateAndApprovePayroll(5, 202501, 10, null))
                .isInstanceOf(PayrollEntityNotFoundException.class)
                .hasMessage("财年 2025 缺少等级 P9 的薪资标准");
    }

    @Test
    void failure_approval_over_limit() {
        when(approverRepository.findById(11)).thenReturn(Optional.of(APPROVER_B_LOW_LIMIT));
        when(employeeRepository.findById(1)).thenReturn(Optional.of(new Employee(1, "张三", "P5", "S")));
        when(salaryStandardRepository.findBaseSalary(FISCAL_YEAR, "P5")).thenReturn(Optional.of(new BigDecimal("18000.00")));

        assertThatThrownBy(() -> service.calculateAndApprovePayroll(1, 202501, 11, null))
                .isInstanceOf(ApprovalLimitExceededException.class)
                .hasMessage("审批金额 27000.00 超过审批人 11 的权限上限 5000.00");

        verify(deductionItemRepository, never()).findByEmployeeAndPeriod(anyInt(), anyInt());
        verify(payrollApprovalRepository, never()).insert(anyInt(), anyInt(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void failure_net_before_tax_negative() {
        when(approverRepository.findById(10)).thenReturn(Optional.of(APPROVER_A));
        when(employeeRepository.findById(3)).thenReturn(Optional.of(new Employee(3, "王五", "P4", "C")));
        when(salaryStandardRepository.findBaseSalary(FISCAL_YEAR, "P4")).thenReturn(Optional.of(new BigDecimal("12000.00")));
        when(deductionItemRepository.findByEmployeeAndPeriod(3, 202501)).thenReturn(List.of(
                new DeductionItem(1, "PENALTY", new BigDecimal("999999.00"), "NORMAL")));

        assertThatThrownBy(() -> service.calculateAndApprovePayroll(3, 202501, 10, BigDecimal.ZERO))
                .isInstanceOf(NegativeNetSalaryException.class)
                .hasMessage("扣款合计 999999.00 超过应发工资 12000.00,数据异常");
    }

    @Test
    void tax_bracket_boundary_upper_inclusive() {
        when(approverRepository.findById(10)).thenReturn(Optional.of(APPROVER_A));
        when(employeeRepository.findById(4)).thenReturn(Optional.of(new Employee(4, "赵六", "P1", "A")));
        when(salaryStandardRepository.findBaseSalary(FISCAL_YEAR, "P1")).thenReturn(Optional.of(new BigDecimal("3000.00")));
        when(deductionItemRepository.findByEmployeeAndPeriod(4, 202599)).thenReturn(List.of());

        // gross = 3000 + override(2000) = 5000,恰好等于第1档上限
        PayrollResult result = service.calculateAndApprovePayroll(4, 202599, 10, new BigDecimal("2000.00"));

        assertThat(result.netSalary()).isEqualByComparingTo("4850.00");
    }

    @Test
    void tax_bracket_boundary_over_by_one() {
        when(approverRepository.findById(10)).thenReturn(Optional.of(APPROVER_A));
        when(employeeRepository.findById(4)).thenReturn(Optional.of(new Employee(4, "赵六", "P1", "A")));
        when(salaryStandardRepository.findBaseSalary(FISCAL_YEAR, "P1")).thenReturn(Optional.of(new BigDecimal("3000.00")));
        when(deductionItemRepository.findByEmployeeAndPeriod(4, 202597)).thenReturn(List.of());

        // gross = 3000 + override(2001) = 5001,比第1档上限多1元
        PayrollResult result = service.calculateAndApprovePayroll(4, 202597, 10, new BigDecimal("2001.00"));

        assertThat(result.netSalary()).isEqualByComparingTo("4850.90");
    }

    @Test
    void tax_bracket_table_empty_zero_tax() {
        when(taxBracketRepository.findByFiscalYear(FISCAL_YEAR)).thenReturn(List.of());
        when(approverRepository.findById(10)).thenReturn(Optional.of(APPROVER_A));
        when(employeeRepository.findById(4)).thenReturn(Optional.of(new Employee(4, "赵六", "P1", "A")));
        when(salaryStandardRepository.findBaseSalary(FISCAL_YEAR, "P1")).thenReturn(Optional.of(new BigDecimal("3000.00")));
        when(deductionItemRepository.findByEmployeeAndPeriod(4, 202598)).thenReturn(List.of());

        PayrollResult result = service.calculateAndApprovePayroll(4, 202598, 10, new BigDecimal("1000.00"));

        assertThat(result.netSalary()).isEqualByComparingTo("4000.00");
    }

    @Test
    void null_employee_id_input() {
        when(approverRepository.findById(10)).thenReturn(Optional.of(APPROVER_A));
        when(employeeRepository.findById(0)).thenReturn(Optional.empty());

        // Java 侧 employeeId 是 int 基元类型,不能直接传 null;用 Integer 拆箱边界(0)
        // 模拟"查不到对应员工"这一等价的可观测行为,真正的 null 语义验证交给 Step 6 直连数据库版。
        assertThatThrownBy(() -> service.calculateAndApprovePayroll(0, 202501, 10, null))
                .isInstanceOf(PayrollEntityNotFoundException.class);
    }

    @Test
    void repeated_call_non_idempotent() {
        when(approverRepository.findById(10)).thenReturn(Optional.of(APPROVER_A));
        when(employeeRepository.findById(2)).thenReturn(Optional.of(new Employee(2, "李四", "P3", "A")));
        when(salaryStandardRepository.findBaseSalary(FISCAL_YEAR, "P3")).thenReturn(Optional.of(new BigDecimal("8000.00")));
        when(deductionItemRepository.findByEmployeeAndPeriod(2, 202503)).thenReturn(List.of());

        PayrollResult first = service.calculateAndApprovePayroll(2, 202503, 10, null);
        PayrollResult second = service.calculateAndApprovePayroll(2, 202503, 10, null);

        assertThat(first.netSalary()).isEqualByComparingTo(second.netSalary());
        verify(payrollApprovalRepository, times(2)).insert(eq(2), eq(202503), eq(10),
                any(), any(), any(), any(), eq("APPROVED"));
    }

    @Test
    void global_var_audit_operator_not_leaked_across_calls() {
        Approver approverA = new Approver(10, "审批人A", new BigDecimal("100000.00"));
        Approver approverB = new Approver(11, "审批人B", new BigDecimal("100000.00"));
        when(approverRepository.findById(10)).thenReturn(Optional.of(approverA));
        when(approverRepository.findById(11)).thenReturn(Optional.of(approverB));
        when(employeeRepository.findById(2)).thenReturn(Optional.of(new Employee(2, "李四", "P3", "A")));
        when(employeeRepository.findById(3)).thenReturn(Optional.of(new Employee(3, "王五", "P4", "C")));
        when(salaryStandardRepository.findBaseSalary(FISCAL_YEAR, "P3")).thenReturn(Optional.of(new BigDecimal("8000.00")));
        when(salaryStandardRepository.findBaseSalary(FISCAL_YEAR, "P4")).thenReturn(Optional.of(new BigDecimal("12000.00")));
        when(deductionItemRepository.findByEmployeeAndPeriod(2, 202503)).thenReturn(List.of());
        when(deductionItemRepository.findByEmployeeAndPeriod(3, 202504)).thenReturn(List.of());

        service.calculateAndApprovePayroll(2, 202503, 10, null);
        service.calculateAndApprovePayroll(3, 202504, 11, BigDecimal.ZERO);

        verify(payrollAuditLogRepository).insert("审批人A", "PAYROLL_START", "开始核算 employee_id=2 period_id=202503");
        verify(payrollAuditLogRepository).insert("审批人B", "PAYROLL_START", "开始核算 employee_id=3 period_id=202504");
    }

    @Test
    void dry_run_branch_currently_unreachable() {
        when(approverRepository.findById(10)).thenReturn(Optional.of(APPROVER_A));
        when(employeeRepository.findById(2)).thenReturn(Optional.of(new Employee(2, "李四", "P3", "A")));
        when(salaryStandardRepository.findBaseSalary(FISCAL_YEAR, "P3")).thenReturn(Optional.of(new BigDecimal("8000.00")));
        when(deductionItemRepository.findByEmployeeAndPeriod(2, 202503)).thenReturn(List.of());

        PayrollResult result = service.calculateAndApprovePayroll(2, 202503, 10, null);

        // g_dry_run 恒为 FALSE 的死代码分支未被实现,当前实现不可能返回 PENDING_REVIEW。
        assertThat(result.status()).isEqualTo("APPROVED");
        verify(payrollApprovalRepository).insert(anyInt(), anyInt(), anyInt(), any(), any(), any(), any(), eq("APPROVED"));
    }
}
