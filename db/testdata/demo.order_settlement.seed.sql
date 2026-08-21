-- demo.settle_order 的测试基线数据。
-- 幂等设计:全部用 INSERT ... ON DUPLICATE KEY UPDATE(openGauss upsert 语法,不是标准
-- PostgreSQL 的 ON CONFLICT,那个在这套 openGauss 兼容模式下不支持),可以重复执行本文件
-- 任意次数而不报主键冲突,也不需要依赖先执行 reset.sql 才能跑。
-- 但 order_items 有外键指向 orders,而 orders.status 会被 settle_order 存储过程/Java 实现
-- 改成 SETTLED——重复执行本文件会把状态和金额都强制改回种子基线值,等价于"重置到已知状态"。
--
-- 交叉验证测试仍然建议 reset.sql(TRUNCATE)+ 本文件一起跑(见 CrossValidationFixtures.resetAndSeed),
-- 这样连"多余的测试订单"(比如批量回归测试插入的 90000 号订单)也会被清掉,不只是把已知订单复位。
-- 本文件设计成幂等,是为了单独执行本文件时(不先 TRUNCATE)也不会报错、结果也可预期。

INSERT INTO demo.customers (customer_id, customer_name, vip_level, balance) VALUES
    (1, 'Alice-Normal', 0, 1000.00)
    ON DUPLICATE KEY UPDATE customer_name = EXCLUDED.customer_name,
                            vip_level = EXCLUDED.vip_level, balance = EXCLUDED.balance;
INSERT INTO demo.customers (customer_id, customer_name, vip_level, balance) VALUES
    (2, 'Bob-VIP', 1, 1000.00)
    ON DUPLICATE KEY UPDATE customer_name = EXCLUDED.customer_name,
                            vip_level = EXCLUDED.vip_level, balance = EXCLUDED.balance;
INSERT INTO demo.customers (customer_id, customer_name, vip_level, balance) VALUES
    (3, 'Carol-SVIP', 2, 1000.00)
    ON DUPLICATE KEY UPDATE customer_name = EXCLUDED.customer_name,
                            vip_level = EXCLUDED.vip_level, balance = EXCLUDED.balance;
INSERT INTO demo.customers (customer_id, customer_name, vip_level, balance) VALUES
    (4, 'Dave-LowBalance', 0, 10.00)
    ON DUPLICATE KEY UPDATE customer_name = EXCLUDED.customer_name,
                            vip_level = EXCLUDED.vip_level, balance = EXCLUDED.balance;

INSERT INTO demo.inventory (product_code, stock_qty) VALUES ('SKU-001', 100)
    ON DUPLICATE KEY UPDATE stock_qty = EXCLUDED.stock_qty;
INSERT INTO demo.inventory (product_code, stock_qty) VALUES ('SKU-002', 5)
    ON DUPLICATE KEY UPDATE stock_qty = EXCLUDED.stock_qty;
INSERT INTO demo.inventory (product_code, stock_qty) VALUES ('SKU-003', 0)
    ON DUPLICATE KEY UPDATE stock_qty = EXCLUDED.stock_qty;

-- 订单1:普通客户,库存充足 -> 预期结算成功,无折扣
INSERT INTO demo.orders (order_id, customer_id, status, total_amount, settled_amount, settled_at) VALUES
    (101, 1, 'PENDING', 200.00, NULL, NULL)
    ON DUPLICATE KEY UPDATE customer_id = EXCLUDED.customer_id, status = EXCLUDED.status,
                            total_amount = EXCLUDED.total_amount,
                            settled_amount = EXCLUDED.settled_amount, settled_at = EXCLUDED.settled_at;
INSERT INTO demo.order_items (item_id, order_id, product_code, quantity, unit_price, stock_deducted) VALUES
    (1001, 101, 'SKU-001', 2, 100.00, FALSE)
    ON DUPLICATE KEY UPDATE order_id = EXCLUDED.order_id, product_code = EXCLUDED.product_code,
                            quantity = EXCLUDED.quantity, unit_price = EXCLUDED.unit_price,
                            stock_deducted = EXCLUDED.stock_deducted;

-- 订单2:VIP客户,库存充足 -> 预期结算成功,95折
INSERT INTO demo.orders (order_id, customer_id, status, total_amount, settled_amount, settled_at) VALUES
    (102, 2, 'PENDING', 300.00, NULL, NULL)
    ON DUPLICATE KEY UPDATE customer_id = EXCLUDED.customer_id, status = EXCLUDED.status,
                            total_amount = EXCLUDED.total_amount,
                            settled_amount = EXCLUDED.settled_amount, settled_at = EXCLUDED.settled_at;
INSERT INTO demo.order_items (item_id, order_id, product_code, quantity, unit_price, stock_deducted) VALUES
    (1002, 102, 'SKU-001', 3, 100.00, FALSE)
    ON DUPLICATE KEY UPDATE order_id = EXCLUDED.order_id, product_code = EXCLUDED.product_code,
                            quantity = EXCLUDED.quantity, unit_price = EXCLUDED.unit_price,
                            stock_deducted = EXCLUDED.stock_deducted;

-- 订单3:SVIP客户,库存不足(SKU-002只有5件,买10件) -> 预期抛异常 P0003,整单回滚
INSERT INTO demo.orders (order_id, customer_id, status, total_amount, settled_amount, settled_at) VALUES
    (103, 3, 'PENDING', 500.00, NULL, NULL)
    ON DUPLICATE KEY UPDATE customer_id = EXCLUDED.customer_id, status = EXCLUDED.status,
                            total_amount = EXCLUDED.total_amount,
                            settled_amount = EXCLUDED.settled_amount, settled_at = EXCLUDED.settled_at;
INSERT INTO demo.order_items (item_id, order_id, product_code, quantity, unit_price, stock_deducted) VALUES
    (1003, 103, 'SKU-002', 10, 50.00, FALSE)
    ON DUPLICATE KEY UPDATE order_id = EXCLUDED.order_id, product_code = EXCLUDED.product_code,
                            quantity = EXCLUDED.quantity, unit_price = EXCLUDED.unit_price,
                            stock_deducted = EXCLUDED.stock_deducted;

-- 订单4:余额不足(Dave只有10元,订单折后金额远超余额) -> 预期抛异常 P0004
INSERT INTO demo.orders (order_id, customer_id, status, total_amount, settled_amount, settled_at) VALUES
    (104, 4, 'PENDING', 100.00, NULL, NULL)
    ON DUPLICATE KEY UPDATE customer_id = EXCLUDED.customer_id, status = EXCLUDED.status,
                            total_amount = EXCLUDED.total_amount,
                            settled_amount = EXCLUDED.settled_amount, settled_at = EXCLUDED.settled_at;
INSERT INTO demo.order_items (item_id, order_id, product_code, quantity, unit_price, stock_deducted) VALUES
    (1004, 104, 'SKU-001', 1, 100.00, FALSE)
    ON DUPLICATE KEY UPDATE order_id = EXCLUDED.order_id, product_code = EXCLUDED.product_code,
                            quantity = EXCLUDED.quantity, unit_price = EXCLUDED.unit_price,
                            stock_deducted = EXCLUDED.stock_deducted;

-- 订单5:状态已经是 SETTLED(非 PENDING) -> 预期抛异常 P0002
INSERT INTO demo.orders (order_id, customer_id, status, total_amount, settled_amount, settled_at) VALUES
    (105, 1, 'SETTLED', 50.00, 50.00, now())
    ON DUPLICATE KEY UPDATE customer_id = EXCLUDED.customer_id, status = EXCLUDED.status,
                            total_amount = EXCLUDED.total_amount,
                            settled_amount = EXCLUDED.settled_amount, settled_at = EXCLUDED.settled_at;
INSERT INTO demo.order_items (item_id, order_id, product_code, quantity, unit_price, stock_deducted) VALUES
    (1005, 105, 'SKU-001', 1, 50.00, FALSE)
    ON DUPLICATE KEY UPDATE order_id = EXCLUDED.order_id, product_code = EXCLUDED.product_code,
                            quantity = EXCLUDED.quantity, unit_price = EXCLUDED.unit_price,
                            stock_deducted = EXCLUDED.stock_deducted;

-- 订单6:多商品订单,全部库存充足,普通客户 -> 覆盖游标多次 FETCH 的路径
INSERT INTO demo.orders (order_id, customer_id, status, total_amount, settled_amount, settled_at) VALUES
    (106, 1, 'PENDING', 250.00, NULL, NULL)
    ON DUPLICATE KEY UPDATE customer_id = EXCLUDED.customer_id, status = EXCLUDED.status,
                            total_amount = EXCLUDED.total_amount,
                            settled_amount = EXCLUDED.settled_amount, settled_at = EXCLUDED.settled_at;
INSERT INTO demo.order_items (item_id, order_id, product_code, quantity, unit_price, stock_deducted) VALUES
    (1006, 106, 'SKU-001', 1, 100.00, FALSE)
    ON DUPLICATE KEY UPDATE order_id = EXCLUDED.order_id, product_code = EXCLUDED.product_code,
                            quantity = EXCLUDED.quantity, unit_price = EXCLUDED.unit_price,
                            stock_deducted = EXCLUDED.stock_deducted;
INSERT INTO demo.order_items (item_id, order_id, product_code, quantity, unit_price, stock_deducted) VALUES
    (1007, 106, 'SKU-002', 1, 50.00, FALSE)
    ON DUPLICATE KEY UPDATE order_id = EXCLUDED.order_id, product_code = EXCLUDED.product_code,
                            quantity = EXCLUDED.quantity, unit_price = EXCLUDED.unit_price,
                            stock_deducted = EXCLUDED.stock_deducted;
INSERT INTO demo.order_items (item_id, order_id, product_code, quantity, unit_price, stock_deducted) VALUES
    (1008, 106, 'SKU-001', 1, 100.00, FALSE)
    ON DUPLICATE KEY UPDATE order_id = EXCLUDED.order_id, product_code = EXCLUDED.product_code,
                            quantity = EXCLUDED.quantity, unit_price = EXCLUDED.unit_price,
                            stock_deducted = EXCLUDED.stock_deducted;

-- 清掉本文件覆盖范围内(order_id 101-106)可能残留的历史结算日志,
-- 保证重复执行本文件后 settlement_log 里不会累积旧的 SETTLED/REJECTED 记录干扰断言。
DELETE FROM demo.settlement_log WHERE order_id BETWEEN 101 AND 106;
