package com.example.migration.repository;

import java.math.BigDecimal;
import java.util.Optional;

public interface SalaryStandardRepository {

    Optional<BigDecimal> findBaseSalary(int fiscalYear, String grade);
}
