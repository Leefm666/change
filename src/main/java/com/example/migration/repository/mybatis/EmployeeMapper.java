package com.example.migration.repository.mybatis;

import com.example.migration.domain.Employee;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EmployeeMapper {

    @Select("SELECT employee_id, employee_name, grade, perf_rating FROM hr.employees WHERE employee_id = #{employeeId}")
    @ConstructorArgs({
            @Arg(column = "employee_id", javaType = int.class),
            @Arg(column = "employee_name", javaType = String.class),
            @Arg(column = "grade", javaType = String.class),
            @Arg(column = "perf_rating", javaType = String.class)
    })
    Employee findById(@Param("employeeId") int employeeId);
}
