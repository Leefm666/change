package com.example.migration.repository;

import com.example.migration.domain.Employee;
import java.util.Optional;

public interface EmployeeRepository {

    Optional<Employee> findById(int employeeId);
}
