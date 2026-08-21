package com.example.migration.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 对应存储过程 demo.apply_vip_discount:按 VIP 等级对金额打折。
 * SVIP(2)=85折,VIP(1)=95折,普通(其余值)=不打折,四舍五入到2位小数。
 */
final class VipDiscountCalculator {

    private static final BigDecimal SVIP_RATE = new BigDecimal("0.85");
    private static final BigDecimal VIP_RATE = new BigDecimal("0.95");

    private VipDiscountCalculator() {
    }

    static BigDecimal applyDiscount(int vipLevel, BigDecimal amount) {
        BigDecimal rate = switch (vipLevel) {
            case 2 -> SVIP_RATE;
            case 1 -> VIP_RATE;
            default -> BigDecimal.ONE;
        };
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
}
