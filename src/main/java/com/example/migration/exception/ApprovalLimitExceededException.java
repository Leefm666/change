package com.example.migration.exception;

import java.math.BigDecimal;

/** 对应存储过程 SQLSTATE P1001:核算金额超过审批人的审批权限上限。 */
public class ApprovalLimitExceededException extends RuntimeException {

    public ApprovalLimitExceededException(BigDecimal amount, int approverId, BigDecimal limit) {
        super("审批金额 " + amount + " 超过审批人 " + approverId + " 的权限上限 " + limit);
    }

    public String sqlState() {
        return "P1001";
    }
}
