package com.example.migration.exception;

/** 对应存储过程 SQLSTATE P0002:订单状态非 PENDING。 */
public class InvalidOrderStatusException extends RuntimeException {

    public InvalidOrderStatusException(String currentStatus) {
        super("订单状态不合法,当前状态: " + currentStatus);
    }

    public String sqlState() {
        return "P0002";
    }
}
