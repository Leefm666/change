package com.example.migration.repository.mybatis;

import java.math.BigDecimal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SalaryStandardMapper {

    @Select("SELECT base_salary FROM hr.salary_standards WHERE fiscal_year = #{fiscalYear} AND grade = #{grade}")
    BigDecimal findBaseSalary(@Param("fiscalYear") int fiscalYear, @Param("grade") String grade);
}
