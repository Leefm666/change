package com.example.migration.support;

import java.sql.Types;

/**
 * 存储过程调用的一个 IN 参数：JDBC 占位符位置(从1开始)、值、SQL 类型(java.sql.Types 常量)。
 */
public record InParam(int position, Object value, int sqlType) {

    public static InParam of(int position, Object value) {
        return new InParam(position, value, Types.OTHER);
    }
}
