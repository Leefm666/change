package com.example.migration.repository.mybatis;

import com.example.migration.repository.InventoryRepository;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisInventoryRepository implements InventoryRepository {

    private final InventoryMapper inventoryMapper;

    public MyBatisInventoryRepository(InventoryMapper inventoryMapper) {
        this.inventoryMapper = inventoryMapper;
    }

    @Override
    public boolean tryDeduct(String productCode, int quantity) {
        return inventoryMapper.tryDeduct(productCode, quantity) > 0;
    }
}
