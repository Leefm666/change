package com.example.migration.repository.mybatis;

import com.example.migration.domain.TaxBracket;
import com.example.migration.repository.TaxBracketRepository;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisTaxBracketRepository implements TaxBracketRepository {

    private final TaxBracketMapper taxBracketMapper;

    public MyBatisTaxBracketRepository(TaxBracketMapper taxBracketMapper) {
        this.taxBracketMapper = taxBracketMapper;
    }

    @Override
    public List<TaxBracket> findByFiscalYear(int fiscalYear) {
        return taxBracketMapper.findByFiscalYear(fiscalYear);
    }
}
