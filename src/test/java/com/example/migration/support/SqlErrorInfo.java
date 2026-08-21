package com.example.migration.support;

/**
 * 存储过程调用抛出异常时的 SQLState + message,用于和 Java 实现抛出的异常语义做对比。
 */
public record SqlErrorInfo(String sqlState, String message) {
}
