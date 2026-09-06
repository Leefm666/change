package com.example.migration.repository.mybatis;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PayrollAuditLogMapper {

    @Insert("INSERT INTO hr.audit_log(operator, action, detail) VALUES (#{operator}, #{action}, #{detail})")
    void insert(@Param("operator") String operator, @Param("action") String action, @Param("detail") String detail);
}
