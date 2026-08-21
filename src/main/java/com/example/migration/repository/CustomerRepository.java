package com.example.migration.repository;

import com.example.migration.domain.Customer;
import java.math.BigDecimal;
import java.util.Optional;

public interface CustomerRepository {

    Optional<Customer> findById(int customerId);

    void deductBalance(int customerId, BigDecimal amount);
}
