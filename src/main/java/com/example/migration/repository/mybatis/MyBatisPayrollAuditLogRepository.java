package com.example.migration.repository.mybatis;

import com.example.migration.repository.PayrollAuditLogRepository;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisPayrollAuditLogRepository implements PayrollAuditLogRepository {

    private final PayrollAuditLogMapper payrollAuditLogMapper;

    public MyBatisPayrollAuditLogRepository(PayrollAuditLogMapper payrollAuditLogMapper) {
        this.payrollAuditLogMapper = payrollAuditLogMapper;
    }

    @Override
    public void insert(String operator, String action, String detail) {
        payrollAuditLogMapper.insert(operator, action, detail);
    }
}
