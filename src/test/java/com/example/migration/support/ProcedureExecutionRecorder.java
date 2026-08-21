package com.example.migration.support;

import java.util.List;

/**
 * 交叉验证测试的通用调用记录接口：给定一次存储过程调用(callSql + 参数),
 * 执行并记录 OUT 参数、结果集、受影响表的行级差异、抛出的异常。
 * 供"调原存储过程"和"调新 Java 实现的等价封装"两侧复用同一套断言逻辑。
 */
public interface ProcedureExecutionRecorder {

    ProcedureExecutionResult invoke(
            String callSql,
            List<InParam> inParams,
            List<OutParamSpec> outParamSpecs,
            List<TableSnapshotSpec> tablesToDiff);
}
