package com.example.migration.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Order(
        int orderId,
        int customerId,
        String status,
        BigDecimal totalAmount,
        BigDecimal settledAmount,
        LocalDateTime settledAt) {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SETTLED = "SETTLED";
}
