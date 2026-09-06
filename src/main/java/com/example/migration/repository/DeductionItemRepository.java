package com.example.migration.repository;

import com.example.migration.domain.DeductionItem;
import java.util.List;

public interface DeductionItemRepository {

    /** 按 deductionId 升序返回,对应存储过程游标的 ORDER BY deduction_id。 */
    List<DeductionItem> findByEmployeeAndPeriod(int employeeId, int periodId);
}
