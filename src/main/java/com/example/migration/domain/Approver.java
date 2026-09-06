package com.example.migration.domain;

import java.math.BigDecimal;

public record Approver(int approverId, String approverName, BigDecimal approvalLimit) {
}
