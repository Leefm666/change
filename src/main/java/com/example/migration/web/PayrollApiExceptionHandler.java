package com.example.migration.web;

import com.example.migration.exception.ApprovalLimitExceededException;
import com.example.migration.exception.NegativeNetSalaryException;
import com.example.migration.exception.PayrollEntityNotFoundException;
import com.example.migration.legacy.LegacyPayrollProcedureFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把 Java 实现的业务异常(P0002/P1001/P1003)和旧存储过程调用失败
 * (LegacyPayrollProcedureFailureException)统一映射成 HTTP 状态码,让调用方不需要关心
 * 这次核算实际走的是哪条路径。P0002(员工/审批人不存在或缺薪资标准)→ 404,其余业务规则
 * 被拒绝(超审批权限/扣款超发薪)以及未知 SQLSTATE → 409。
 */
@RestControllerAdvice
public class PayrollApiExceptionHandler {

    @ExceptionHandler({
            PayrollEntityNotFoundException.class,
            ApprovalLimitExceededException.class,
            NegativeNetSalaryException.class,
            LegacyPayrollProcedureFailureException.class
    })
    public ResponseEntity<ErrorResponse> handlePayrollFailure(RuntimeException exception) {
        String sqlState = switch (exception) {
            case PayrollEntityNotFoundException e -> e.sqlState();
            case ApprovalLimitExceededException e -> e.sqlState();
            case NegativeNetSalaryException e -> e.sqlState();
            case LegacyPayrollProcedureFailureException e -> e.sqlState();
            default -> null;
        };
        HttpStatus status = "P0002".equals(sqlState) ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(new ErrorResponse(sqlState, exception.getMessage()));
    }
}
