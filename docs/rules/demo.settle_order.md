# demo.settle_order 业务规则推导

## 签名
- IN p_order_id INT
- OUT o_final_amount NUMERIC
- OUT o_status VARCHAR

## 分支/业务含义逐条对照
1. 订单不存在 -> 抛异常 SQLSTATE P0001,消息"订单不存在: {order_id}"
2. 订单状态 <> PENDING -> 先写 settlement_log(event='REJECTED'),再抛异常 P0002
3. 按 item_id 升序遍历订单项(游标):对每条尝试 `UPDATE inventory SET stock_qty = stock_qty - qty WHERE product_code=... AND stock_qty >= qty`
   - 未命中(库存不足) -> 写 settlement_log(event='STOCK_SHORTAGE'),抛异常 P0003,终止游标遍历
   - 命中 -> 标记该订单项 stock_deducted=TRUE,继续下一条
4. 调用子过程 apply_vip_discount(vip_level, total_amount) 计算折后金额:SVIP(2)=×0.85,VIP(1)=×0.95,普通(0)=×1.0,四舍五入到2位小数
5. 若客户余额 < 折后金额 -> 写 settlement_log(event='INSUFFICIENT_BALANCE'),抛异常 P0004
6. 否则:扣客户余额,订单状态改 SETTLED 并记录 settled_amount/settled_at,写 settlement_log(event='SETTLED')

## 边界条件清单
- null 输入:p_order_id 为 NULL 时 `SELECT ... WHERE order_id = NULL` 不会命中任何行,走"订单不存在"分支。
- 空结果集(订单无任何 order_items):游标一次都不 FETCH 成功,直接跳过循环,不影响后续折扣/余额判断。
- 重复调用幂等性:非幂等。订单一旦 SETTLED,第二次调用会因状态检查在第2步直接失败(P0002)。
- 隐式类型转换:total_amount/unit_price 均为 NUMERIC(12,2),折扣计算用 ROUND(amount*rate, 2),与 Java `BigDecimal.setScale(2, RoundingMode.HALF_UP)` 语义一致(round half away from zero,对正数等价于 HALF_UP)。

## 交叉验证发现的隐藏行为(关键,人工读代码容易漏)
**在真实 openGauss 上验证发现**:整个存储过程体只有一个 `EXCEPTION WHEN OTHERS ... RAISE;` 块包住全部逻辑。PL/pgSQL 的异常处理块等价于一个隐式 SAVEPOINT,`RAISE` 重新抛出异常导致这个 SAVEPOINT 被回滚——也就是说,**REJECTED / STOCK_SHORTAGE / INSUFFICIENT_BALANCE 这三条失败路径里写入 settlement_log 的记录,最终都会被回滚,不会真正持久化到表里**。

验证过程:分别调用订单103(库存不足)、104(余额不足)、105(状态非法)结算,三次都抛出预期异常;但调用结束后查询 `demo.settlement_log`,只看到订单101/102/106这三个**成功**结算的 SETTLED 记录,REJECTED/STOCK_SHORTAGE/INSUFFICIENT_BALANCE 一条都没有留下。

这是人工读代码时容易得出的错误预期(以为失败路径的日志会保留下来用于排查问题),必须用真实数据库交叉验证才能发现。

**Java 实现取此真实行为为准**:`OrderSettlementService.settleOrder` 整体运行在一个 Spring `@Transactional` 事务里,失败路径依然调用 `settlementLogRepository.insert(...)` 然后抛未检查异常,由 Spring 的事务回滚统一抹掉这次插入——**用同一种机制(事务回滚)复现同一种可观察行为**,而不是靠"失败分支干脆不 insert"这种表面等价但机制不同的补丁。

## 依赖(来自 docs/inventory/dependency-map.csv)
- 表:demo.customers, demo.orders, demo.order_items, demo.inventory, demo.settlement_log
- 触发器:无
- 序列:demo.settlement_log_log_id_seq(隐式,SERIAL 列)
- 嵌套调用:demo.apply_vip_discount(function)

## 复杂度评分与分级
- branch(IF/CASE) ×2: 4个IF分支(P0001/P0002/P0004 + NOT FOUND) + CASE(discount) ≈ 5 → 10
- loop ×3: 1个游标LOOP → 3
- cursor ×4: 1个游标 → 4
- exception_block ×3: 1个 → 3
- nested_call ×2: 1个(apply_vip_discount) → 2
- distinct_table ×1: 5张表 → 5
- score ≈ 27 → **Tier 3(高风险核心)**,需要完整 TDD 微流程 + 交叉验证 + 批量随机回归(第5节)

## 待确认项(需业务方裁决,不由开发自行决定)
- 无(demo 场景,已按"真实数据库行为为准"自行裁决;真实业务迁移时,若发现类似"失败路径日志被回滚"的行为,必须回头找业务方确认这是否是故意设计,而不是自动照搬)
