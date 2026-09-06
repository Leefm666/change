package com.example.migration.repository;

import com.example.migration.domain.TaxBracket;
import java.util.List;

public interface TaxBracketRepository {

    /** 按 bracketOrder 升序返回,调用方按此顺序逐档累进计税。 */
    List<TaxBracket> findByFiscalYear(int fiscalYear);
}
