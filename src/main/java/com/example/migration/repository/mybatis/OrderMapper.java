package com.example.migration.repository.mybatis;

import com.example.migration.domain.Order;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * MyBatis Mapper:只做参数化 CRUD,不含业务逻辑(业务逻辑在 OrderSettlementService)。
 * 用 @ConstructorArgs 显式映射到 Order record 的构造参数,避免依赖自动映射猜列名。
 */
@Mapper
public interface OrderMapper {

    @Select("SELECT order_id, customer_id, status, total_amount, settled_amount, settled_at "
            + "FROM demo.orders WHERE order_id = #{orderId}")
    @ConstructorArgs({
            @Arg(column = "order_id", javaType = int.class),
            @Arg(column = "customer_id", javaType = int.class),
            @Arg(column = "status", javaType = String.class),
            @Arg(column = "total_amount", javaType = BigDecimal.class),
            @Arg(column = "settled_amount", javaType = BigDecimal.class),
            @Arg(column = "settled_at", javaType = java.time.LocalDateTime.class)
    })
    Order findById(@Param("orderId") int orderId);

    @Update("UPDATE demo.orders SET status = 'SETTLED', settled_amount = #{settledAmount}, settled_at = now() "
            + "WHERE order_id = #{orderId}")
    void markSettled(@Param("orderId") int orderId, @Param("settledAmount") BigDecimal settledAmount);
}
