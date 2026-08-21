package com.example.migration.repository.mybatis;

import com.example.migration.domain.Customer;
import com.example.migration.repository.CustomerRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisCustomerRepository implements CustomerRepository {

    private final CustomerMapper customerMapper;

    public MyBatisCustomerRepository(CustomerMapper customerMapper) {
        this.customerMapper = customerMapper;
    }

    @Override
    public Optional<Customer> findById(int customerId) {
        return Optional.ofNullable(customerMapper.findById(customerId));
    }

    @Override
    public void deductBalance(int customerId, BigDecimal amount) {
        customerMapper.deductBalance(customerId, amount);
    }
}
