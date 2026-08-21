package com.example.migration.repository.mybatis;

import com.example.migration.domain.Order;
import com.example.migration.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisOrderRepository implements OrderRepository {

    private final OrderMapper orderMapper;

    public MyBatisOrderRepository(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Override
    public Optional<Order> findById(int orderId) {
        return Optional.ofNullable(orderMapper.findById(orderId));
    }

    @Override
    public void markSettled(int orderId, BigDecimal settledAmount) {
        orderMapper.markSettled(orderId, settledAmount);
    }
}
