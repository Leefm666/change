package com.example.migration.exception;

/** 对应存储过程 SQLSTATE P0003:商品库存不足。 */
public class StockShortageException extends RuntimeException {

    public StockShortageException(String productCode) {
        super("商品库存不足: " + productCode);
    }

    public String sqlState() {
        return "P0003";
    }
}
