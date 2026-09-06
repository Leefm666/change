package com.example.migration.repository.mybatis;

import com.example.migration.repository.SalaryStandardRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisSalaryStandardRepository implements SalaryStandardRepository {

    private final SalaryStandardMapper salaryStandardMapper;

    public MyBatisSalaryStandardRepository(SalaryStandardMapper salaryStandardMapper) {
        this.salaryStandardMapper = salaryStandardMapper;
    }

    @Override
    public Optional<BigDecimal> findBaseSalary(int fiscalYear, String grade) {
        return Optional.ofNullable(salaryStandardMapper.findBaseSalary(fiscalYear, grade));
    }
}
