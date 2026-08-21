package com.example.migration.support;

import java.util.List;
import java.util.Map;

/**
 * 一张表在存储过程调用前后的行级差异。
 * added/removed/changed 中的行以主键列 -> 值 的 Map 形式表示,changed 记录调用后的完整行。
 */
public record TableDiff(
        String tableName,
        List<Map<String, Object>> addedRows,
        List<Map<String, Object>> removedRows,
        List<Map<String, Object>> changedRows) {

    public boolean isEmpty() {
        return addedRows.isEmpty() && removedRows.isEmpty() && changedRows.isEmpty();
    }
}
