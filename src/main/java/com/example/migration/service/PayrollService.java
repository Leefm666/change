package com.example.migration.service;

import com.example.migration.domain.Approver;
import com.example.migration.domain.DeductionItem;
import com.example.migration.domain.Employee;
import com.example.migration.domain.PayrollResult;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * hr.hr_payroll.calculate_and_approve_payroll 的 Java 等价实现。
 *
 * 全局变量映射(详见 docs/rules/hr.hr_payroll.calculate_and_approve_payroll.md):
 * g_fiscal_year -> FISCAL_YEAR 方法内常量(源码硬编码2025,是否改为入参属于待确认项,未获业务方裁决前原样保留);
 * g_audit_operator -> writeAuditLog 的显式参数,不做字段;
 * g_dry_run -> 恒为 FALSE 的死代码分支(隐藏行为3),本次不实现 PENDING_REVIEW 分支,
 *   待业务方就"待确认项"给出结论后再决定是否补上。
 *
 * 失败路径依然先写审计日志再抛异常,由外层 @Transactional 统一回滚——复现存储过程
 * "EXCEPTION WHEN OTHERS RAISE" 隐式 SAVEPOINT 导致失败路径日志不落库的真实行为
 * (隐藏行为2,已通过真实 openGauss 交叉验证确认)。
 *
 * package 内 SELECT INTO 查询无结果时,openGauss 自动抛异常(隐藏行为1),源码里
 * P2001/P2002/P2003 三条 NOT FOUND 分支和 COALESCE 兜底均为死代码,Java 侧不翻译
 * 这些死代码,统一用 PayrollEntityNotFoundException(SQLSTATE P0002)复现实测行为。
 */
@Service
public class PayrollService {

    /** 对应源码硬编码的 g_fiscal_year := 2025。 */
    private static final int FISCAL_YEAR = 2025;

    private final EmployeeRepository employeeRepository;
    private final ApproverRepository approverRepository;
    private final SalaryStandardRepository salaryStandardRepository;
    private final TaxBracketRepository taxBracketRepository;
    private final DeductionItemRepository deductionItemRepository;
    private final PayrollApprovalRepository payrollApprovalRepository;
    private final PayrollAuditLogRepository payrollAuditLogRepository;

    public PayrollService(
            EmployeeRepository employeeRepository,
            ApproverRepository approverRepository,
            SalaryStandardRepository salaryStandardRepository,
            TaxBracketRepository taxBracketRepository,
            DeductionItemRepository deductionItemRepository,
            PayrollApprovalRepository payrollApprovalRepository,
            PayrollAuditLogRepository payrollAuditLogRepository) {
        this.employeeRepository = employeeRepository;
        this.approverRepository = approverRepository;
        this.salaryStandardRepository = salaryStandardRepository;
        this.taxBracketRepository = taxBracketRepository;
        this.deductionItemRepository = deductionItemRepository;
        this.payrollApprovalRepository = payrollApprovalRepository;
        this.payrollAuditLogRepository = payrollAuditLogRepository;
    }

    @Transactional
    public PayrollResult calculateAndApprovePayroll(
            int employeeId, int periodId, int approverId, BigDecimal bonusOverride) {

        Approver approver = approverRepository.findById(approverId)
                .orElseThrow(() -> new PayrollEntityNotFoundException("审批人不存在: " + approverId));
        String auditOperator = approver.approverName();

        payrollAuditLogRepository.insert(auditOperator, "PAYROLL_START",
                "开始核算 employee_id=" + employeeId + " period_id=" + periodId);

        BigDecimal gross = calculateBaseSalary(employeeId)
                .add(calculateBonus(employeeId, bonusOverride));

        if (gross.compareTo(approver.approvalLimit()) > 0) {
            throw new ApprovalLimitExceededException(gross, approverId, approver.approvalLimit());
        }

        BigDecimal deductions = deductItemsLoop(employeeId, periodId, auditOperator);

        BigDecimal netBeforeTax = gross.subtract(deductions);
        if (netBeforeTax.signum() < 0) {
            throw new NegativeNetSalaryException(deductions, gross);
        }

        BigDecimal tax = ProgressiveTaxCalculator.calculate(
                netBeforeTax, taxBracketRepository.findByFiscalYear(FISCAL_YEAR));
        BigDecimal net = netBeforeTax.subtract(tax);

        payrollApprovalRepository.insert(
                employeeId, periodId, approverId, gross, deductions, tax, net, PayrollResult.STATUS_APPROVED);

        payrollAuditLogRepository.insert(auditOperator, "PAYROLL_APPROVED", "核算通过,净额=" + net);

        return new PayrollResult(net, PayrollResult.STATUS_APPROVED);
    }

    private BigDecimal calculateBaseSalary(int employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new PayrollEntityNotFoundException("员工不存在: " + employeeId));
        return salaryStandardRepository.findBaseSalary(FISCAL_YEAR, employee.grade())
                .orElseThrow(() -> new PayrollEntityNotFoundException(
                        "财年 " + FISCAL_YEAR + " 缺少等级 " + employee.grade() + " 的薪资标准"));
    }

    private BigDecimal calculateBonus(int employeeId, BigDecimal bonusOverride) {
        if (bonusOverride != null) {
            return bonusOverride;
        }
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new PayrollEntityNotFoundException("员工不存在: " + employeeId));
        BigDecimal baseSalary = calculateBaseSalary(employeeId);
        return BonusCalculator.calculate(employee.perfRating(), baseSalary, null);
    }

    private BigDecimal deductItemsLoop(int employeeId, int periodId, String auditOperator) {
        BigDecimal total = BigDecimal.ZERO;
        for (DeductionItem item : deductionItemRepository.findByEmployeeAndPeriod(employeeId, periodId)) {
            if (DeductionItem.STATUS_DISPUTED.equals(item.itemStatus())) {
                payrollAuditLogRepository.insert(auditOperator, "DEDUCTION_SKIPPED",
                        "跳过争议中的扣款项 deduction_id=" + item.deductionId());
                continue;
            }
            total = total.add(item.amount());
        }
        return total;
    }
}
