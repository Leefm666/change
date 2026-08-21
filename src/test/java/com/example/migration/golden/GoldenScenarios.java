package com.example.migration.golden;

import java.util.List;

/**
 * 黄金测试集覆盖哪些用例(输入定义),对应 db/testdata/demo.order_settlement.seed.sql
 * 里预先设计好覆盖各分支的订单。只定义 name/orderId/description,预期输出字段留空——
 * 预期输出必须来自真实调用存储过程(见 GoldenDatasetStalenessCheckIT),不是手写猜测。
 */
public final class GoldenScenarios {

    private GoldenScenarios() {
    }

    public static final List<GoldenScenario> DEFINITIONS = List.of(
            new GoldenScenario("normal_customer_no_discount", 101,
                    "普通客户,库存充足,无VIP折扣", null, null, null, null, null),
            new GoldenScenario("vip_customer_95_percent_discount", 102,
                    "VIP客户,库存充足,95折", null, null, null, null, null),
            new GoldenScenario("stock_shortage_rejected", 103,
                    "SVIP客户但商品库存不足,应整单回滚并抛异常", null, null, null, null, null),
            new GoldenScenario("insufficient_balance_rejected", 104,
                    "普通客户但账户余额不足,应整单回滚并抛异常", null, null, null, null, null),
            new GoldenScenario("already_settled_rejected", 105,
                    "订单已是SETTLED状态,重复结算应被拒绝", null, null, null, null, null),
            new GoldenScenario("multiple_items_cursor_loop", 106,
                    "多商品订单,验证游标遍历多条order_items并逐条扣库存", null, null, null, null, null));
}
