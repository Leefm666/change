package com.example.migration.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对一组表在存储过程调用前后各拍一次快照(按主键排序的全表 SELECT),
 * 再按主键对齐算出新增/删除/变更的行。用于交叉验证测试判断存储过程的表级副作用。
 */
public final class TableSnapshotDiffer {

    private TableSnapshotDiffer() {
    }

    /** key: tableName, value: 该表所有行,每行是 列名 -> 值 的 Map,按主键顺序排列。 */
    public static Map<String, List<Map<String, Object>>> captureSnapshots(
            Connection connection, List<TableSnapshotSpec> specs) throws SQLException {

        Map<String, List<Map<String, Object>>> snapshots = new LinkedHashMap<>();
        for (TableSnapshotSpec spec : specs) {
            snapshots.put(spec.tableName(), captureOne(connection, spec));
        }
        return snapshots;
    }

    private static List<Map<String, Object>> captureOne(Connection connection, TableSnapshotSpec spec)
            throws SQLException {
        String orderBy = String.join(", ", spec.primaryKeyColumns());
        String sql = "SELECT * FROM " + spec.tableName() + " ORDER BY " + orderBy;

        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    public static List<TableDiff> diff(
            Map<String, List<Map<String, Object>>> before,
            Map<String, List<Map<String, Object>>> after,
            List<TableSnapshotSpec> specs) {

        List<TableDiff> diffs = new ArrayList<>();
        for (TableSnapshotSpec spec : specs) {
            diffs.add(diffOne(spec, before.get(spec.tableName()), after.get(spec.tableName())));
        }
        return diffs;
    }

    private static TableDiff diffOne(
            TableSnapshotSpec spec, List<Map<String, Object>> before, List<Map<String, Object>> after) {

        Map<Object, Map<String, Object>> beforeByKey = indexByPrimaryKey(before, spec.primaryKeyColumns());
        Map<Object, Map<String, Object>> afterByKey = indexByPrimaryKey(after, spec.primaryKeyColumns());

        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();

        for (Map.Entry<Object, Map<String, Object>> entry : afterByKey.entrySet()) {
            Map<String, Object> beforeRow = beforeByKey.get(entry.getKey());
            if (beforeRow == null) {
                added.add(entry.getValue());
            } else if (!beforeRow.equals(entry.getValue())) {
                changed.add(entry.getValue());
            }
        }
        for (Map.Entry<Object, Map<String, Object>> entry : beforeByKey.entrySet()) {
            if (!afterByKey.containsKey(entry.getKey())) {
                removed.add(entry.getValue());
            }
        }

        return new TableDiff(spec.tableName(), added, removed, changed);
    }

    private static Map<Object, Map<String, Object>> indexByPrimaryKey(
            List<Map<String, Object>> rows, List<String> primaryKeyColumns) {

        Map<Object, Map<String, Object>> index = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            List<Object> key = new ArrayList<>(primaryKeyColumns.size());
            for (String pkColumn : primaryKeyColumns) {
                key.add(row.get(pkColumn));
            }
            index.put(key, row);
        }
        return index;
    }
}
