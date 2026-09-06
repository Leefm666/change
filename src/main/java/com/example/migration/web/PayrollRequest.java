package com.example.migration.web;

import java.math.BigDecimal;

/** p_bonus_override 允许 null,对应存储过程"未手动指定奖金则由系统按绩效等级计算"的语义。 */
public record PayrollRequest(int employeeId, int periodId, int approverId, BigDecimal bonusOverride) {
}
