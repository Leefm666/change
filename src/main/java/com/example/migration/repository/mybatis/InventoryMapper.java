package com.example.migration.repository.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InventoryMapper {

    /** 对应存储过程里 UPDATE inventory ... WHERE stock_qty >= qty 的条件扣减,返回受影响行数。 */
    @Update("UPDATE demo.inventory SET stock_qty = stock_qty - #{quantity} "
            + "WHERE product_code = #{productCode} AND stock_qty >= #{quantity}")
    int tryDeduct(@Param("productCode") String productCode, @Param("quantity") int quantity);
}
