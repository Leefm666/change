package com.example.migration.domain;

import java.math.BigDecimal;

public record TaxBracket(int bracketOrder, BigDecimal upperBound, BigDecimal taxRate) {
}
