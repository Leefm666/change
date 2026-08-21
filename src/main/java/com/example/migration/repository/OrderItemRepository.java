package com.example.migration.repository;

import com.example.migration.domain.OrderItem;
import java.util.List;

public interface OrderItemRepository {

    /** 按 item_id 升序返回,对应存储过程游标 ORDER BY item_id。 */
    List<OrderItem> findByOrderId(int orderId);

    void markStockDeducted(int itemId);
}
