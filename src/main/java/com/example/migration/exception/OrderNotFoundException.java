package com.example.migration.exception;

/** 对应存储过程 SQLSTATE P0001:订单不存在。 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(int orderId) {
        super("订单不存在: " + orderId);
    }

    /** 对应原存储过程 RAISE EXCEPTION ... USING ERRCODE,供交叉验证测试比较异常语义。 */
    public String sqlState() {
        return "P0001";
    }
}
