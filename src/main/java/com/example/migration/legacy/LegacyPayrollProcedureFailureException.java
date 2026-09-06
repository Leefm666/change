package com.example.migration.legacy;

/**
 * hr.hr_payroll 存储过程调用失败(SQLException)的封装,携带 SQLSTATE 供上层按错误类型
 * 映射 HTTP 状态码,语义上对应 Java 侧的 PayrollEntityNotFoundException/
 * ApprovalLimitExceededException/NegativeNetSalaryException 那组 P0002/P1001/P1003。
 */
public class LegacyPayrollProcedureFailureException extends RuntimeException {

    private final String sqlState;

    public LegacyPayrollProcedureFailureException(String sqlState, String message, Throwable cause) {
        super(message, cause);
        this.sqlState = sqlState;
    }

    public String sqlState() {
        return sqlState;
    }
}
