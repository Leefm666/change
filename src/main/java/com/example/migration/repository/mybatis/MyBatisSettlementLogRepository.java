package com.example.migration.repository.mybatis;

import com.example.migration.repository.SettlementLogRepository;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisSettlementLogRepository implements SettlementLogRepository {

    private final SettlementLogMapper settlementLogMapper;

    public MyBatisSettlementLogRepository(SettlementLogMapper settlementLogMapper) {
        this.settlementLogMapper = settlementLogMapper;
    }

    @Override
    public void insert(int orderId, String event, String detail) {
        settlementLogMapper.insert(orderId, event, detail);
    }
}
