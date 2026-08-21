package com.example.migration.repository;

import com.example.migration.domain.Order;
import java.math.BigDecimal;
import java.util.Optional;

public interface OrderRepository {

    Optional<Order> findById(int orderId);

    void markSettled(int orderId, BigDecimal settledAmount);
}
