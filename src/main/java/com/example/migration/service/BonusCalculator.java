package com.example.migration.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 对应包体私有函数 hr_payroll.calc_bonus:override 非 null 时直接采用;
 * 否则按绩效等级系数 × 基本工资(S=50%,A=30%,B=15%,C及其余值=0%)。
 */
final class BonusCalculator {

    private static final BigDecimal S_RATE = new BigDecimal("0.50");
    private static final BigDecimal A_RATE = new BigDecimal("0.30");
    private static final BigDecimal B_RATE = new BigDecimal("0.15");

    private BonusCalculator() {
    }

    static BigDecimal calculate(String perfRating, BigDecimal baseSalary, BigDecimal override) {
        if (override != null) {
            return override;
        }
        BigDecimal coefficient = switch (perfRating) {
            case "S" -> S_RATE;
            case "A" -> A_RATE;
            case "B" -> B_RATE;
            default -> BigDecimal.ZERO;
        };
        return baseSalary.multiply(coefficient).setScale(2, RoundingMode.HALF_UP);
    }
}
