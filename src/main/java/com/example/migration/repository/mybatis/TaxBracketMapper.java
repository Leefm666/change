package com.example.migration.repository.mybatis;

import com.example.migration.domain.TaxBracket;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TaxBracketMapper {

    @Select("SELECT bracket_order, upper_bound, tax_rate FROM hr.tax_brackets "
            + "WHERE fiscal_year = #{fiscalYear} ORDER BY bracket_order")
    @ConstructorArgs({
            @Arg(column = "bracket_order", javaType = int.class),
            @Arg(column = "upper_bound", javaType = BigDecimal.class),
            @Arg(column = "tax_rate", javaType = BigDecimal.class)
    })
    List<TaxBracket> findByFiscalYear(@Param("fiscalYear") int fiscalYear);
}
