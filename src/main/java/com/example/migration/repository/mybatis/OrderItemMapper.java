package com.example.migration.repository.mybatis;

import com.example.migration.domain.OrderItem;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderItemMapper {

    /** 按 item_id 升序返回,对应存储过程游标 ORDER BY item_id。 */
    @Select("SELECT item_id, order_id, product_code, quantity, unit_price, stock_deducted "
            + "FROM demo.order_items WHERE order_id = #{orderId} ORDER BY item_id")
    @ConstructorArgs({
            @Arg(column = "item_id", javaType = int.class),
            @Arg(column = "order_id", javaType = int.class),
            @Arg(column = "product_code", javaType = String.class),
            @Arg(column = "quantity", javaType = int.class),
            @Arg(column = "unit_price", javaType = BigDecimal.class),
            @Arg(column = "stock_deducted", javaType = boolean.class)
    })
    List<OrderItem> findByOrderId(@Param("orderId") int orderId);

    @Update("UPDATE demo.order_items SET stock_deducted = TRUE WHERE item_id = #{itemId}")
    void markStockDeducted(@Param("itemId") int itemId);
}
