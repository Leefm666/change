package com.example.migration.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.migration.domain.SettlementResult;
import com.example.migration.exception.InsufficientBalanceException;
import com.example.migration.exception.InvalidOrderStatusException;
import com.example.migration.exception.OrderNotFoundException;
import com.example.migration.exception.StockShortageException;
import com.example.migration.legacy.LegacyProcedureFailureException;
import com.example.migration.routing.SettlementOutcome;
import com.example.migration.routing.SettlementOutcome.RouteTarget;
import com.example.migration.routing.SettlementRoutingService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {SettlementController.class, SettlementApiExceptionHandler.class})
class SettlementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SettlementRoutingService routingService;

    @Test
    void settle_success_returnsResultAndRoutedTo() throws Exception {
        when(routingService.settleOrder(101)).thenReturn(new SettlementOutcome(
                new SettlementResult(new BigDecimal("200.00"), "SETTLED"), RouteTarget.LEGACY_PROCEDURE));

        mockMvc.perform(post("/api/orders/{orderId}/settlement", 101))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalAmount").value(200.00))
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.routedTo").value("LEGACY_PROCEDURE"));
    }

    @Test
    void settle_orderNotFound_returns404WithSqlState() throws Exception {
        when(routingService.settleOrder(404)).thenThrow(new OrderNotFoundException(404));

        mockMvc.perform(post("/api/orders/{orderId}/settlement", 404))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.sqlState").value("P0001"));
    }

    @Test
    void settle_invalidOrderStatus_returns409WithSqlState() throws Exception {
        when(routingService.settleOrder(105)).thenThrow(new InvalidOrderStatusException("SETTLED"));

        mockMvc.perform(post("/api/orders/{orderId}/settlement", 105))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.sqlState").value("P0002"));
    }

    @Test
    void settle_stockShortage_returns409WithSqlState() throws Exception {
        when(routingService.settleOrder(103)).thenThrow(new StockShortageException("SKU-002"));

        mockMvc.perform(post("/api/orders/{orderId}/settlement", 103))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.sqlState").value("P0003"));
    }

    @Test
    void settle_insufficientBalance_returns409WithSqlState() throws Exception {
        when(routingService.settleOrder(104)).thenThrow(
                new InsufficientBalanceException(new BigDecimal("100.00"), new BigDecimal("10.00")));

        mockMvc.perform(post("/api/orders/{orderId}/settlement", 104))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.sqlState").value("P0004"));
    }

    @Test
    void settle_legacyProcedureFailure_returns409WithSqlState() throws Exception {
        when(routingService.settleOrder(eq(106))).thenThrow(
                new LegacyProcedureFailureException("P0003", "商品库存不足: SKU-002", null));

        mockMvc.perform(post("/api/orders/{orderId}/settlement", 106))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.sqlState").value("P0003"));
    }
}
