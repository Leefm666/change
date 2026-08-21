package com.example.migration.domain;

import java.math.BigDecimal;

public record SettlementResult(BigDecimal finalAmount, String status) {
}
