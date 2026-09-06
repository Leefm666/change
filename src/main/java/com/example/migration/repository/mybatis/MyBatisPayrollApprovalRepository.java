package com.example.migration.repository.mybatis;

import com.example.migration.repository.PayrollApprovalRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisPayrollApprovalRepository implements PayrollApprovalRepository {

    private final PayrollApprovalMapper payrollApprovalMapper;

    public MyBatisPayrollApprovalRepository(PayrollApprovalMapper payrollApprovalMapper) {
        this.payrollApprovalMapper = payrollApprovalMapper;
    }

    @Override
    public void insert(
            int employeeId,
            int periodId,
            int approverId,
            BigDecimal grossAmount,
            BigDecimal deductionAmount,
            BigDecimal taxAmount,
            BigDecimal netAmount,
            String status) {
        payrollApprovalMapper.insert(
                employeeId, periodId, approverId, grossAmount, deductionAmount, taxAmount, netAmount, status);
    }
}
