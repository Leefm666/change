package com.example.migration.support;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 通过 JDBC CallableStatement 直接调用真实 openGauss 存储过程的 Recorder 实现。
 * 调用前后对 tablesToDiff 做快照对比,调用中抛出的 SQLException 转换为 SqlErrorInfo 而不向上抛,
 * 以便交叉验证测试统一比较"是否报错"和错误语义,而不是被异常中断。
 */
public final class JdbcProcedureExecutionRecorder implements ProcedureExecutionRecorder {

    private final Supplier<Connection> connectionSupplier;

    public JdbcProcedureExecutionRecorder(Supplier<Connection> connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
    }

    @Override
    public ProcedureExecutionResult invoke(
            String callSql,
            List<InParam> inParams,
            List<OutParamSpec> outParamSpecs,
            List<TableSnapshotSpec> tablesToDiff) {

        try (Connection connection = connectionSupplier.get()) {
            connection.setAutoCommit(false);
            Map<String, List<Map<String, Object>>> before =
                    TableSnapshotDiffer.captureSnapshots(connection, tablesToDiff);

            try (CallableStatement statement = connection.prepareCall(callSql)) {
                for (InParam param : inParams) {
                    statement.setObject(param.position(), param.value(), param.sqlType());
                }
                for (OutParamSpec outParam : outParamSpecs) {
                    statement.registerOutParameter(outParam.position(), outParam.sqlType());
                }

                List<Map<String, Object>> resultSetRows = new ArrayList<>();
                boolean hasResultSet = statement.execute();
                if (hasResultSet) {
                    try (ResultSet rs = statement.getResultSet()) {
                        resultSetRows = readResultSet(rs);
                    }
                }

                Map<String, Object> outParams = new LinkedHashMap<>();
                for (OutParamSpec outParam : outParamSpecs) {
                    outParams.put(outParam.name(), statement.getObject(outParam.position()));
                }

                Map<String, List<Map<String, Object>>> after =
                        TableSnapshotDiffer.captureSnapshots(connection, tablesToDiff);
                List<TableDiff> tableDiffs = TableSnapshotDiffer.diff(before, after, tablesToDiff);

                connection.commit();
                return new ProcedureExecutionResult(outParams, resultSetRows, tableDiffs, Optional.empty());

            } catch (SQLException callFailure) {
                connection.rollback();
                Map<String, List<Map<String, Object>>> after =
                        TableSnapshotDiffer.captureSnapshots(connection, tablesToDiff);
                List<TableDiff> tableDiffs = TableSnapshotDiffer.diff(before, after, tablesToDiff);
                SqlErrorInfo error = new SqlErrorInfo(callFailure.getSQLState(), callFailure.getMessage());
                return new ProcedureExecutionResult(Map.of(), List.of(), tableDiffs, Optional.of(error));
            }
        } catch (SQLException connectionFailure) {
            throw new IllegalStateException("openGauss 连接或快照采集失败", connectionFailure);
        }
    }

    private static List<Map<String, Object>> readResultSet(ResultSet rs) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }
}
