package com.example.migration.support;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一次存储过程调用(或等价 Java 实现调用)的完整可比对结果。
 */
public record ProcedureExecutionResult(
        Map<String, Object> outParams,
        List<Map<String, Object>> resultSetRows,
        List<TableDiff> tableDiffs,
        Optional<SqlErrorInfo> thrownError) {
}
