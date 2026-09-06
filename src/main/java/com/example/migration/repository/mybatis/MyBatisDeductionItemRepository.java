package com.example.migration.repository.mybatis;

import com.example.migration.domain.DeductionItem;
import com.example.migration.repository.DeductionItemRepository;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisDeductionItemRepository implements DeductionItemRepository {

    private final DeductionItemMapper deductionItemMapper;

    public MyBatisDeductionItemRepository(DeductionItemMapper deductionItemMapper) {
        this.deductionItemMapper = deductionItemMapper;
    }

    @Override
    public List<DeductionItem> findByEmployeeAndPeriod(int employeeId, int periodId) {
        return deductionItemMapper.findByEmployeeAndPeriod(employeeId, periodId);
    }
}
