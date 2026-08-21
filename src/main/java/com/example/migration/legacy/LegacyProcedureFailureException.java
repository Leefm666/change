package com.example.migration.legacy;

/**
 * demo.settle_order 存储过程调用失败(SQLException)的封装,携带 SQLSTATE 供上层按错误类型
 * 映射 HTTP 状态码,语义上对应 Java 侧的 OrderNotFoundException/InvalidOrderStatusException/
 * StockShortageException/InsufficientBalanceException 那组 P0001-P0004。
 */
public class LegacyProcedureFailureException extends RuntimeException {

    private final String sqlState;

    public LegacyProcedureFailureException(String sqlState, String message, Throwable cause) {
        super(message, cause);
        this.sqlState = sqlState;
    }

    public String sqlState() {
        return sqlState;
    }
}
