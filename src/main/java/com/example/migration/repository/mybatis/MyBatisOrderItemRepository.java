package com.example.migration.repository.mybatis;

import com.example.migration.domain.OrderItem;
import com.example.migration.repository.OrderItemRepository;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisOrderItemRepository implements OrderItemRepository {

    private final OrderItemMapper orderItemMapper;

    public MyBatisOrderItemRepository(OrderItemMapper orderItemMapper) {
        this.orderItemMapper = orderItemMapper;
    }

    @Override
    public List<OrderItem> findByOrderId(int orderId) {
        return orderItemMapper.findByOrderId(orderId);
    }

    @Override
    public void markStockDeducted(int itemId) {
        orderItemMapper.markStockDeducted(itemId);
    }
}
