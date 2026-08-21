package com.example.migration.exception;

import java.math.BigDecimal;

/** 对应存储过程 SQLSTATE P0004:客户余额不足。 */
public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(BigDecimal required, BigDecimal current) {
        super("客户余额不足,需要 " + required + " 现有 " + current);
    }

    public String sqlState() {
        return "P0004";
    }
}
