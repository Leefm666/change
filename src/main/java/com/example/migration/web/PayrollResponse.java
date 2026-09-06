package com.example.migration.web;

import com.example.migration.routing.PayrollOutcome;
import java.math.BigDecimal;

public record PayrollResponse(BigDecimal netSalary, String status, String routedTo) {

    public static PayrollResponse from(PayrollOutcome outcome) {
        return new PayrollResponse(
                outcome.result().netSalary(),
                outcome.result().status(),
                outcome.routedTo().name());
    }
}
