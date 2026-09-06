package com.example.migration.repository;

public interface PayrollAuditLogRepository {

    void insert(String operator, String action, String detail);
}
