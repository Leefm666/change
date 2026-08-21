package com.example.migration.support;

/**
 * 存储过程调用的一个 OUT/INOUT 参数声明：JDBC 占位符位置(从1开始)、参数名(用于结果 Map 的 key)、SQL 类型。
 */
public record OutParamSpec(int position, String name, int sqlType) {
}
