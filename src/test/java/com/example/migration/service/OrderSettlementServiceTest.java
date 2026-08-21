package com.example.migration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 断言值来自人工阅读 db/legacy-procedures/demo.order_settlement.sql 推导的预期结果,
 * 并已通过 docs/rules/demo.settle_order.md 记录的真实 openGauss 交叉验证确认一致。
 */
@ExtendWith(MockitoExtension.class)
class OrderSettlementServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private SettlementLogRepository settlementLogRepository;

    private OrderSettlementService service;

    @BeforeEach
    void setUp() {
        service = new OrderSettlementService(
                orderRepository, customerRepository, orderItemRepository,
                inventoryRepository, settlementLogRepository);
    }

    @Test
    void settleOrder_normalCustomer_noDiscount() {
        when(orderRepository.findById(101)).thenReturn(Optional.of(
                new Order(101, 1, Order.STATUS_PENDING, new BigDecimal("200.00"), null, null)));
        when(customerRepository.findById(1)).thenReturn(Optional.of(
                new Customer(1, "Alice-Normal", 0, new BigDecimal("1000.00"))));
        when(orderItemRepository.findByOrderId(101)).thenReturn(List.of(
                new OrderItem(1001, 101, "SKU-001", 2, new BigDecimal("100.00"), false)));
        when(inventoryRepository.tryDeduct("SKU-001", 2)).thenReturn(true);

        SettlementResult result = service.settleOrder(101);

        assertThat(result.finalAmount()).isEqualByComparingTo("200.00");
        assertThat(result.status()).isEqualTo("SETTLED");
        verify(customerRepository).deductBalance(1, new BigDecimal("200.00"));
        verify(orderRepository).markSettled(101, new BigDecimal("200.00"));
        verify(settlementLogRepository).insert(101, "SETTLED", "结算金额: 200.00");
    }

    @Test
    void settleOrder_vipCustomer_appliesNinetyFivePercentDiscount() {
        when(orderRepository.findById(102)).thenReturn(Optional.of(
                new Order(102, 2, Order.STATUS_PENDING, new BigDecimal("300.00"), null, null)));
        when(customerRepository.findById(2)).thenReturn(Optional.of(
                new Customer(2, "Bob-VIP", 1, new BigDecimal("1000.00"))));
        when(orderItemRepository.findByOrderId(102)).thenReturn(List.of(
                new OrderItem(1002, 102, "SKU-001", 3, new BigDecimal("100.00"), false)));
        when(inventoryRepository.tryDeduct("SKU-001", 3)).thenReturn(true);

        SettlementResult result = service.settleOrder(102);

        assertThat(result.finalAmount()).isEqualByComparingTo("285.00");
    }

    @Test
    void settleOrder_svipCustomer_appliesEightyFivePercentDiscount() {
        when(orderRepository.findById(999)).thenReturn(Optional.of(
                new Order(999, 3, Order.STATUS_PENDING, new BigDecimal("500.00"), null, null)));
        when(customerRepository.findById(3)).thenReturn(Optional.of(
                new Customer(3, "Carol-SVIP", 2, new BigDecimal("1000.00"))));
        when(orderItemRepository.findByOrderId(999)).thenReturn(List.of());

        SettlementResult result = service.settleOrder(999);

        assertThat(result.finalAmount()).isEqualByComparingTo("425.00");
    }

    @Test
    void settleOrder_orderNotFound_throwsAndSkipsAllSideEffects() {
        when(orderRepository.findById(404)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.settleOrder(404))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessage("订单不存在: 404");

        verify(settlementLogRepository, never()).insert(anyInt(), any(), any());
        verify(customerRepository, never()).deductBalance(anyInt(), any());
    }

    @Test
    void settleOrder_alreadySettled_throwsInvalidStatusAndLogsRejection() {
        when(orderRepository.findById(105)).thenReturn(Optional.of(
                new Order(105, 1, "SETTLED", new BigDecimal("50.00"), new BigDecimal("50.00"), null)));

        assertThatThrownBy(() -> service.settleOrder(105))
                .isInstanceOf(InvalidOrderStatusException.class)
                .hasMessage("订单状态不合法,当前状态: SETTLED");

        verify(settlementLogRepository).insert(105, "REJECTED", "订单状态非 PENDING: SETTLED");
        verify(customerRepository, never()).deductBalance(anyInt(), any());
    }

    @Test
    void settleOrder_stockShortage_throwsAndStopsAtFirstShortItem() {
        when(orderRepository.findById(103)).thenReturn(Optional.of(
                new Order(103, 3, Order.STATUS_PENDING, new BigDecimal("500.00"), null, null)));
        when(customerRepository.findById(3)).thenReturn(Optional.of(
                new Customer(3, "Carol-SVIP", 2, new BigDecimal("1000.00"))));
        when(orderItemRepository.findByOrderId(103)).thenReturn(List.of(
                new OrderItem(1003, 103, "SKU-002", 10, new BigDecimal("50.00"), false)));
        when(inventoryRepository.tryDeduct("SKU-002", 10)).thenReturn(false);

        assertThatThrownBy(() -> service.settleOrder(103))
                .isInstanceOf(StockShortageException.class)
                .hasMessage("商品库存不足: SKU-002");

        verify(settlementLogRepository).insert(103, "STOCK_SHORTAGE", "库存不足: SKU-002");
        verify(orderItemRepository, never()).markStockDeducted(anyInt());
        verify(customerRepository, never()).deductBalance(anyInt(), any());
    }

    @Test
    void settleOrder_insufficientBalance_throwsAndLogsShortfall() {
        when(orderRepository.findById(104)).thenReturn(Optional.of(
                new Order(104, 4, Order.STATUS_PENDING, new BigDecimal("100.00"), null, null)));
        when(customerRepository.findById(4)).thenReturn(Optional.of(
                new Customer(4, "Dave-LowBalance", 0, new BigDecimal("10.00"))));
        when(orderItemRepository.findByOrderId(104)).thenReturn(List.of(
                new OrderItem(1004, 104, "SKU-001", 1, new BigDecimal("100.00"), false)));
        when(inventoryRepository.tryDeduct("SKU-001", 1)).thenReturn(true);

        assertThatThrownBy(() -> service.settleOrder(104))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessage("客户余额不足,需要 100.00 现有 10.00");

        verify(settlementLogRepository).insert(104, "INSUFFICIENT_BALANCE", "余额不足,需要: 100.00 现有: 10.00");
        verify(customerRepository, never()).deductBalance(anyInt(), any());
        verify(orderRepository, never()).markSettled(anyInt(), any());
    }

    @Test
    void settleOrder_multipleItems_deductsStockForEachViaCursorEquivalentLoop() {
        when(orderRepository.findById(106)).thenReturn(Optional.of(
                new Order(106, 1, Order.STATUS_PENDING, new BigDecimal("250.00"), null, null)));
        when(customerRepository.findById(1)).thenReturn(Optional.of(
                new Customer(1, "Alice-Normal", 0, new BigDecimal("1000.00"))));
        when(orderItemRepository.findByOrderId(106)).thenReturn(List.of(
                new OrderItem(1006, 106, "SKU-001", 1, new BigDecimal("100.00"), false),
                new OrderItem(1007, 106, "SKU-002", 1, new BigDecimal("50.00"), false),
                new OrderItem(1008, 106, "SKU-001", 1, new BigDecimal("100.00"), false)));
        when(inventoryRepository.tryDeduct(eq("SKU-001"), eq(1))).thenReturn(true);
        when(inventoryRepository.tryDeduct(eq("SKU-002"), eq(1))).thenReturn(true);

        SettlementResult result = service.settleOrder(106);

        assertThat(result.finalAmount()).isEqualByComparingTo("250.00");
        verify(orderItemRepository).markStockDeducted(1006);
        verify(orderItemRepository).markStockDeducted(1007);
        verify(orderItemRepository).markStockDeducted(1008);
    }
}
