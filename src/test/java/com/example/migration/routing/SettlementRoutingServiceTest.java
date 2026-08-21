package com.example.migration.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.migration.domain.SettlementResult;
import com.example.migration.legacy.LegacySettleOrderProcedureGateway;
import com.example.migration.routing.SettlementOutcome.RouteTarget;
import com.example.migration.service.OrderSettlementService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementRoutingServiceTest {

    @Mock
    private OrderSettlementService javaService;
    @Mock
    private LegacySettleOrderProcedureGateway legacyGateway;

    private MigrationSwitchProperties properties;
    private SettlementRoutingService routingService;

    @BeforeEach
    void setUp() {
        properties = new MigrationSwitchProperties();
        routingService = new SettlementRoutingService(properties, javaService, legacyGateway);
    }

    @Test
    void settleOrder_switchDisabled_alwaysRoutesToLegacyProcedure() {
        properties.setEnabled(false);
        properties.setRolloutPercentage(100);
        when(legacyGateway.call(101)).thenReturn(new SettlementResult(new BigDecimal("200.00"), "SETTLED"));

        SettlementOutcome outcome = routingService.settleOrder(101);

        assertThat(outcome.routedTo()).isEqualTo(RouteTarget.LEGACY_PROCEDURE);
        verify(javaService, never()).settleOrder(anyInt());
    }

    @Test
    void settleOrder_enabledButZeroPercentRollout_alwaysRoutesToLegacyProcedure() {
        properties.setEnabled(true);
        properties.setRolloutPercentage(0);
        when(legacyGateway.call(101)).thenReturn(new SettlementResult(new BigDecimal("200.00"), "SETTLED"));

        SettlementOutcome outcome = routingService.settleOrder(101);

        assertThat(outcome.routedTo()).isEqualTo(RouteTarget.LEGACY_PROCEDURE);
        verify(javaService, never()).settleOrder(anyInt());
    }

    @Test
    void settleOrder_enabledFullRollout_alwaysRoutesToJava() {
        properties.setEnabled(true);
        properties.setRolloutPercentage(100);
        when(javaService.settleOrder(101)).thenReturn(new SettlementResult(new BigDecimal("200.00"), "SETTLED"));

        SettlementOutcome outcome = routingService.settleOrder(101);

        assertThat(outcome.routedTo()).isEqualTo(RouteTarget.JAVA);
        verify(legacyGateway, never()).call(anyInt());
    }

    @Test
    void settleOrder_partialRollout_sameOrderIdAlwaysRoutesTheSameWay() {
        properties.setEnabled(true);
        properties.setRolloutPercentage(50);
        // 只有一条分支会真正被调用(取决于 orderId 哈希落在哪一侧),用 lenient 避免另一条的
        // stub 被判定为未使用——这个用例关心的是"同一个 orderId 三次路由结果相同",不关心具体是哪一侧。
        lenient().when(javaService.settleOrder(anyInt()))
                .thenReturn(new SettlementResult(new BigDecimal("1.00"), "SETTLED"));
        lenient().when(legacyGateway.call(anyInt()))
                .thenReturn(new SettlementResult(new BigDecimal("1.00"), "SETTLED"));

        RouteTarget first = routingService.settleOrder(202).routedTo();
        RouteTarget second = routingService.settleOrder(202).routedTo();
        RouteTarget third = routingService.settleOrder(202).routedTo();

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
    }
}
