package com.example.migration.domain;

import java.math.BigDecimal;

public record OrderItem(
        int itemId,
        int orderId,
        String productCode,
        int quantity,
        BigDecimal unitPrice,
        boolean stockDeducted) {
}
