package com.example.migration.repository;

/** 对应存储过程里 UPDATE inventory ... WHERE stock_qty >= qty 的条件扣减。 */
public interface InventoryRepository {

    /** 尝试扣减库存,返回是否命中(即库存是否足够)。 */
    boolean tryDeduct(String productCode, int quantity);
}
