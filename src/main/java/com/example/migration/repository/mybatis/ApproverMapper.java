package com.example.migration.repository.mybatis;

import com.example.migration.domain.Approver;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ApproverMapper {

    @Select("SELECT approver_id, approver_name, approval_limit FROM hr.approvers WHERE approver_id = #{approverId}")
    @ConstructorArgs({
            @Arg(column = "approver_id", javaType = int.class),
            @Arg(column = "approver_name", javaType = String.class),
            @Arg(column = "approval_limit", javaType = BigDecimal.class)
    })
    Approver findById(@Param("approverId") int approverId);
}
