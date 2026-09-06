package com.example.migration.domain;

import java.math.BigDecimal;

public record PayrollResult(BigDecimal netSalary, String status) {

    public static final String STATUS_APPROVED = "APPROVED";
}
