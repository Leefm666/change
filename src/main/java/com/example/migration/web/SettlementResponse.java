package com.example.migration.web;

import com.example.migration.routing.SettlementOutcome;
import java.math.BigDecimal;

public record SettlementResponse(BigDecimal finalAmount, String status, String routedTo) {

    public static SettlementResponse from(SettlementOutcome outcome) {
        return new SettlementResponse(
                outcome.result().finalAmount(),
                outcome.result().status(),
                outcome.routedTo().name());
    }
}
