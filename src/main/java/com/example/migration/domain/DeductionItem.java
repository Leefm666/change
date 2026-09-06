package com.example.migration.domain;

import java.math.BigDecimal;

public record DeductionItem(int deductionId, String itemType, BigDecimal amount, String itemStatus) {

    public static final String STATUS_DISPUTED = "DISPUTED";
}
