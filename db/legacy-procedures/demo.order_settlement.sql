-- 演示用复杂存储过程:订单结算 settle_order
-- 覆盖:条件分支、游标遍历、异常处理、嵌套调用其他存储过程、对多张表的写操作
-- 用于验证 "openGauss 存储过程 -> Java TDD 迁移" 全流程的 demo。

CREATE SCHEMA IF NOT EXISTS demo;

CREATE TABLE IF NOT EXISTS demo.customers (
    customer_id     INT PRIMARY KEY,
    customer_name   VARCHAR(100) NOT NULL,
    vip_level       INT NOT NULL DEFAULT 0,      -- 0=普通, 1=VIP, 2=SVIP
    balance         NUMERIC(12,2) NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS demo.orders (
    order_id        INT PRIMARY KEY,
    customer_id     INT NOT NULL REFERENCES demo.customers(customer_id),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING/SETTLED/CANCELLED
    total_amount    NUMERIC(12,2) NOT NULL,
    settled_amount  NUMERIC(12,2),
    settled_at      TIMESTAMP
);

CREATE TABLE IF NOT EXISTS demo.order_items (
    item_id         INT PRIMARY KEY,
    order_id        INT NOT NULL REFERENCES demo.orders(order_id),
    product_code    VARCHAR(50) NOT NULL,
    quantity        INT NOT NULL,
    unit_price      NUMERIC(12,2) NOT NULL,
    stock_deducted  BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS demo.inventory (
    product_code    VARCHAR(50) PRIMARY KEY,
    stock_qty       INT NOT NULL
);

CREATE TABLE IF NOT EXISTS demo.settlement_log (
    log_id          SERIAL PRIMARY KEY,
    order_id        INT NOT NULL,
    event           VARCHAR(50) NOT NULL,
    detail          TEXT,
    logged_at       TIMESTAMP NOT NULL DEFAULT now()
);

-- 嵌套调用的子存储过程:按 VIP 等级计算折扣后金额
CREATE OR REPLACE FUNCTION demo.apply_vip_discount(
    p_vip_level INT,
    p_amount NUMERIC
) RETURNS NUMERIC
LANGUAGE plpgsql
AS $$
DECLARE
    v_discount_rate NUMERIC;
BEGIN
    CASE p_vip_level
        WHEN 2 THEN v_discount_rate := 0.85;  -- SVIP 85折
        WHEN 1 THEN v_discount_rate := 0.95;  -- VIP 95折
        ELSE v_discount_rate := 1.0;
    END CASE;
    RETURN ROUND(p_amount * v_discount_rate, 2);
END;
$$;

-- 主存储过程:订单结算
-- 业务规则:
--   1. 订单必须是 PENDING 状态,否则抛异常(状态不合法不结算)
--   2. 逐条遍历订单项(游标),对每个商品扣减库存;若某商品库存不足,记录日志并整单回滚抛异常
--   3. 按客户 VIP 等级对订单总额打折(调用 apply_vip_discount),折后金额从客户余额扣除
--   4. 若客户余额不足,记录日志并整单回滚抛异常
--   5. 全部成功后:订单状态改为 SETTLED,记录 settled_amount/settled_at,写入结算日志
CREATE OR REPLACE PROCEDURE demo.settle_order(
    IN  p_order_id INT,
    OUT o_final_amount NUMERIC,
    OUT o_status VARCHAR
)
AS
DECLARE
    v_order         demo.orders%ROWTYPE;
    v_customer      demo.customers%ROWTYPE;
    v_item          RECORD;
    v_final_amount  NUMERIC(12,2);
    CURSOR v_item_cursor IS
        SELECT item_id, product_code, quantity, unit_price
        FROM demo.order_items
        WHERE order_id = p_order_id
        ORDER BY item_id;
BEGIN
    SELECT * INTO v_order FROM demo.orders WHERE order_id = p_order_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION '订单不存在: %', p_order_id USING ERRCODE = 'P0001';
    END IF;

    IF v_order.status <> 'PENDING' THEN
        INSERT INTO demo.settlement_log(order_id, event, detail)
        VALUES (p_order_id, 'REJECTED', '订单状态非 PENDING: ' || v_order.status);
        RAISE EXCEPTION '订单状态不合法,当前状态: %', v_order.status USING ERRCODE = 'P0002';
    END IF;

    SELECT * INTO v_customer FROM demo.customers WHERE customer_id = v_order.customer_id;

    OPEN v_item_cursor;
    LOOP
        FETCH v_item_cursor INTO v_item;
        EXIT WHEN NOT FOUND;

        UPDATE demo.inventory
           SET stock_qty = stock_qty - v_item.quantity
         WHERE product_code = v_item.product_code
           AND stock_qty >= v_item.quantity;

        IF NOT FOUND THEN
            INSERT INTO demo.settlement_log(order_id, event, detail)
            VALUES (p_order_id, 'STOCK_SHORTAGE', '库存不足: ' || v_item.product_code);
            CLOSE v_item_cursor;
            RAISE EXCEPTION '商品库存不足: %', v_item.product_code USING ERRCODE = 'P0003';
        END IF;

        UPDATE demo.order_items
           SET stock_deducted = TRUE
         WHERE item_id = v_item.item_id;
    END LOOP;
    CLOSE v_item_cursor;

    v_final_amount := demo.apply_vip_discount(v_customer.vip_level, v_order.total_amount);

    IF v_customer.balance < v_final_amount THEN
        INSERT INTO demo.settlement_log(order_id, event, detail)
        VALUES (p_order_id, 'INSUFFICIENT_BALANCE',
                '余额不足,需要: ' || v_final_amount || ' 现有: ' || v_customer.balance);
        RAISE EXCEPTION '客户余额不足,需要 % 现有 %', v_final_amount, v_customer.balance
            USING ERRCODE = 'P0004';
    END IF;

    UPDATE demo.customers
       SET balance = balance - v_final_amount
     WHERE customer_id = v_order.customer_id;

    UPDATE demo.orders
       SET status = 'SETTLED',
           settled_amount = v_final_amount,
           settled_at = now()
     WHERE order_id = p_order_id;

    INSERT INTO demo.settlement_log(order_id, event, detail)
    VALUES (p_order_id, 'SETTLED', '结算金额: ' || v_final_amount);

    o_final_amount := v_final_amount;
    o_status := 'SETTLED';
EXCEPTION
    WHEN OTHERS THEN
        o_final_amount := NULL;
        o_status := 'FAILED: ' || SQLERRM;
        RAISE;
END;
/
