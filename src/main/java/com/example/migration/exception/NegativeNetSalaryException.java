package com.example.migration.exception;

import java.math.BigDecimal;

/** 对应存储过程 SQLSTATE P1003:扣款合计超过应发工资,数据异常。 */
public class NegativeNetSalaryException extends RuntimeException {

    public NegativeNetSalaryException(BigDecimal deductions, BigDecimal gross) {
        super("扣款合计 " + deductions + " 超过应发工资 " + gross + ",数据异常");
    }

    public String sqlState() {
        return "P1003";
    }
}
