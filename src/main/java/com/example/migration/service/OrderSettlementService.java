package com.example.migration.service;

import com.example.migration.domain.Customer;
import com.example.migration.domain.Order;
import com.example.migration.domain.OrderItem;
import com.example.migration.domain.SettlementResult;
import com.example.migration.exception.InsufficientBalanceException;
import com.example.migration.exception.InvalidOrderStatusException;
import com.example.migration.exception.OrderNotFoundException;
import com.example.migration.exception.StockShortageException;
import com.example.migration.repository.CustomerRepository;
import com.example.migration.repository.InventoryRepository;
import com.example.migration.repository.OrderItemRepository;
import com.example.migration.repository.OrderRepository;
import com.example.migration.repository.SettlementLogRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * demo.settle_order 存储过程的 Java 等价实现。
 *
 * 失败路径(REJECTED/STOCK_SHORTAGE/INSUFFICIENT_BALANCE)依然先写结算日志再抛异常,
 * 由外层 @Transactional 统一回滚——这是刻意保留的行为,复现了原存储过程里
 * "EXCEPTION WHEN OTHERS RAISE" 隐式 SAVEPOINT 回滚导致失败日志不落库的真实副作用
 * (该行为已通过真实 openGauss 交叉验证确认,详见 docs/rules/demo.settle_order.md)。
 */
@Service
public class OrderSettlementService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final OrderItemRepository orderItemRepository;
    private final InventoryRepository inventoryRepository;
    private final SettlementLogRepository settlementLogRepository;

    public OrderSettlementService(
            OrderRepository orderRepository,
            CustomerRepository customerRepository,
            OrderItemRepository orderItemRepository,
            InventoryRepository inventoryRepository,
            SettlementLogRepository settlementLogRepository) {
        this.orderRepository = orderRepository;
        this.customerRepository = customerRepository;
        this.orderItemRepository = orderItemRepository;
        this.inventoryRepository = inventoryRepository;
        this.settlementLogRepository = settlementLogRepository;
    }

    @Transactional
    public SettlementResult settleOrder(int orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (!Order.STATUS_PENDING.equals(order.status())) {
            settlementLogRepository.insert(orderId, "REJECTED", "订单状态非 PENDING: " + order.status());
            throw new InvalidOrderStatusException(order.status());
        }

        Customer customer = customerRepository.findById(order.customerId())
                .orElseThrow(() -> new OrderNotFoundException(order.customerId()));

        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        for (OrderItem item : items) {
            boolean deducted = inventoryRepository.tryDeduct(item.productCode(), item.quantity());
            if (!deducted) {
                settlementLogRepository.insert(orderId, "STOCK_SHORTAGE", "库存不足: " + item.productCode());
                throw new StockShortageException(item.productCode());
            }
            orderItemRepository.markStockDeducted(item.itemId());
        }

        BigDecimal finalAmount = VipDiscountCalculator.applyDiscount(customer.vipLevel(), order.totalAmount());

        if (customer.balance().compareTo(finalAmount) < 0) {
            settlementLogRepository.insert(orderId, "INSUFFICIENT_BALANCE",
                    "余额不足,需要: " + finalAmount + " 现有: " + customer.balance());
            throw new InsufficientBalanceException(finalAmount, customer.balance());
        }

        customerRepository.deductBalance(order.customerId(), finalAmount);
        orderRepository.markSettled(orderId, finalAmount);
        settlementLogRepository.insert(orderId, "SETTLED", "结算金额: " + finalAmount);

        return new SettlementResult(finalAmount, "SETTLED");
    }
}
