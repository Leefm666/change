package com.example.migration.service;

import com.example.migration.domain.TaxBracket;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 对应包体私有函数 hr_payroll.calc_progressive_tax:分级累进税率,upper_bound 是
 * "档位上限且含边界"(闭区间),超出上限的部分才进入下一档。
 * upper_bound=null 表示最高档,无上限,剩余部分全额按该档税率计税。
 * 税率表为空时返回 0(已实测的真实行为,不是异常,详见规则文档"边界条件清单")。
 */
final class ProgressiveTaxCalculator {

    private ProgressiveTaxCalculator() {
    }

    static BigDecimal calculate(BigDecimal grossTaxable, List<TaxBracket> brackets) {
        BigDecimal remaining = grossTaxable;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal prevUpper = BigDecimal.ZERO;

        for (TaxBracket bracket : brackets) {
            if (remaining.signum() <= 0) {
                break;
            }
            if (bracket.upperBound() == null) {
                tax = tax.add(round(remaining.multiply(bracket.taxRate())));
                remaining = BigDecimal.ZERO;
            } else {
                BigDecimal bracketSpan = bracket.upperBound().subtract(prevUpper);
                BigDecimal taxableInBand = remaining.min(bracketSpan);
                tax = tax.add(round(taxableInBand.multiply(bracket.taxRate())));
                remaining = remaining.subtract(taxableInBand);
                prevUpper = bracket.upperBound();
            }
        }
        return tax;
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
