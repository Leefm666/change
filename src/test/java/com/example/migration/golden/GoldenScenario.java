package com.example.migration.golden;

/**
 * 黄金测试集里的一条用例:一个 orderId 对应存储过程在当前种子数据下的真实输出。
 * expectedOutcome=SUCCESS 时 expectedFinalAmount/expectedStatus 有值;
 * =FAILURE 时 expectedSqlState/expectedErrorMessageContains 有值。
 */
public record GoldenScenario(
        String name,
        int orderId,
        String description,
        Outcome expectedOutcome,
        String expectedFinalAmount,
        String expectedStatus,
        String expectedSqlState,
        String expectedErrorMessageContains) {

    public enum Outcome {
        SUCCESS,
        FAILURE
    }
}
