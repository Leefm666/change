package com.example.migration.repository.mybatis;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SettlementLogMapper {

    @Insert("INSERT INTO demo.settlement_log(order_id, event, detail) "
            + "VALUES (#{orderId}, #{event}, #{detail})")
    void insert(@Param("orderId") int orderId, @Param("event") String event, @Param("detail") String detail);
}
