package com.example.migration.domain;

import java.math.BigDecimal;

public record Customer(int customerId, String customerName, int vipLevel, BigDecimal balance) {
}
