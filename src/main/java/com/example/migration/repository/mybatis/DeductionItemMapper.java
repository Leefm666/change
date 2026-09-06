package com.example.migration.repository.mybatis;

import com.example.migration.domain.DeductionItem;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DeductionItemMapper {

    @Select("SELECT deduction_id, item_type, amount, item_status FROM hr.deduction_items "
            + "WHERE employee_id = #{employeeId} AND period_id = #{periodId} ORDER BY deduction_id")
    @ConstructorArgs({
            @Arg(column = "deduction_id", javaType = int.class),
            @Arg(column = "item_type", javaType = String.class),
            @Arg(column = "amount", javaType = BigDecimal.class),
            @Arg(column = "item_status", javaType = String.class)
    })
    List<DeductionItem> findByEmployeeAndPeriod(@Param("employeeId") int employeeId, @Param("periodId") int periodId);
}
