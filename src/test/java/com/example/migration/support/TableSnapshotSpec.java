package com.example.migration.support;

import java.util.List;

/**
 * 描述一张需要在存储过程调用前后做快照对比的表：表名 + 主键列(用于按主键排序对齐行)。
 */
public record TableSnapshotSpec(String tableName, List<String> primaryKeyColumns) {
}
