package com.example.migration.repository.mybatis;

import java.math.BigDecimal;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PayrollApprovalMapper {

    @Insert("INSERT INTO hr.payroll_approvals("
            + "employee_id, period_id, approver_id, gross_amount, deduction_amount, tax_amount, net_amount, status) "
            + "VALUES (#{employeeId}, #{periodId}, #{approverId}, #{grossAmount}, #{deductionAmount}, "
            + "#{taxAmount}, #{netAmount}, #{status})")
    void insert(
            @Param("employeeId") int employeeId,
            @Param("periodId") int periodId,
            @Param("approverId") int approverId,
            @Param("grossAmount") BigDecimal grossAmount,
            @Param("deductionAmount") BigDecimal deductionAmount,
            @Param("taxAmount") BigDecimal taxAmount,
            @Param("netAmount") BigDecimal netAmount,
            @Param("status") String status);
}
