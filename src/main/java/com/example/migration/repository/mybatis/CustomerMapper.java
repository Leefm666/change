package com.example.migration.repository.mybatis;

import com.example.migration.domain.Customer;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CustomerMapper {

    @Select("SELECT customer_id, customer_name, vip_level, balance "
            + "FROM demo.customers WHERE customer_id = #{customerId}")
    @ConstructorArgs({
            @Arg(column = "customer_id", javaType = int.class),
            @Arg(column = "customer_name", javaType = String.class),
            @Arg(column = "vip_level", javaType = int.class),
            @Arg(column = "balance", javaType = BigDecimal.class)
    })
    Customer findById(@Param("customerId") int customerId);

    @Update("UPDATE demo.customers SET balance = balance - #{amount} WHERE customer_id = #{customerId}")
    void deductBalance(@Param("customerId") int customerId, @Param("amount") BigDecimal amount);
}
