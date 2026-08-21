package com.example.migration.web;

import com.example.migration.exception.InsufficientBalanceException;
import com.example.migration.exception.InvalidOrderStatusException;
import com.example.migration.exception.OrderNotFoundException;
import com.example.migration.exception.StockShortageException;
import com.example.migration.legacy.LegacyProcedureFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把 Java 实现的四个业务异常(P0001-P0004)和旧存储过程调用失败(LegacyProcedureFailureException)
 * 统一映射成 HTTP 状态码,让调用方不需要关心这次结算实际走的是哪条路径。
 * P0001(订单不存在)→ 404,其余业务规则被拒绝(状态不合法/库存不足/余额不足)以及未知
 * SQLSTATE → 409(语义上都是"当前状态下无法完成这个操作",不是请求本身格式错误)。
 */
@RestControllerAdvice
public class SettlementApiExceptionHandler {

    @ExceptionHandler({
            OrderNotFoundException.class,
            InvalidOrderStatusException.class,
            StockShortageException.class,
            InsufficientBalanceException.class,
            LegacyProcedureFailureException.class
    })
    public ResponseEntity<ErrorResponse> handleSettlementFailure(RuntimeException exception) {
        String sqlState = switch (exception) {
            case OrderNotFoundException e -> e.sqlState();
            case InvalidOrderStatusException e -> e.sqlState();
            case StockShortageException e -> e.sqlState();
            case InsufficientBalanceException e -> e.sqlState();
            case LegacyProcedureFailureException e -> e.sqlState();
            default -> null;
        };
        HttpStatus status = "P0001".equals(sqlState) ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(new ErrorResponse(sqlState, exception.getMessage()));
    }
}
