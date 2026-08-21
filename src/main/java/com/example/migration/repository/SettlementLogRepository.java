package com.example.migration.repository;

public interface SettlementLogRepository {

    void insert(int orderId, String event, String detail);
}
