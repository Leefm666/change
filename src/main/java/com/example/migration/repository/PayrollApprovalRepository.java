package com.example.migration.repository;

import java.math.BigDecimal;

public interface PayrollApprovalRepository {

    void insert(
            int employeeId,
            int periodId,
            int approverId,
            BigDecimal grossAmount,
            BigDecimal deductionAmount,
            BigDecimal taxAmount,
            BigDecimal netAmount,
            String status);
}
