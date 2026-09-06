package com.example.migration.repository.mybatis;

import com.example.migration.domain.Employee;
import com.example.migration.repository.EmployeeRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisEmployeeRepository implements EmployeeRepository {

    private final EmployeeMapper employeeMapper;

    public MyBatisEmployeeRepository(EmployeeMapper employeeMapper) {
        this.employeeMapper = employeeMapper;
    }

    @Override
    public Optional<Employee> findById(int employeeId) {
        return Optional.ofNullable(employeeMapper.findById(employeeId));
    }
}
