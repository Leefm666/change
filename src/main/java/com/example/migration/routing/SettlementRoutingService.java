package com.example.migration.routing;

import com.example.migration.domain.SettlementResult;
import com.example.migration.legacy.LegacySettleOrderProcedureGateway;
import com.example.migration.routing.SettlementOutcome.RouteTarget;
import com.example.migration.service.OrderSettlementService;
import org.springframework.stereotype.Service;

/**
 * demo.settle_order 迁移的灰度路由入口:按 MigrationSwitchProperties 决定这次调用
 * 走新 Java 实现还是旧存储过程。不加 @Transactional——两条分支各自管理自己的事务边界
 * (OrderSettlementService 内部 @Transactional;LegacySettleOrderProcedureGateway 走
 * 独立 autocommit 连接),路由本身不应该把二者并到同一个事务里。
 */
@Service
public class SettlementRoutingService {

    private final MigrationSwitchProperties properties;
    private final OrderSettlementService javaService;
    private final LegacySettleOrderProcedureGateway legacyGateway;

    public SettlementRoutingService(
            MigrationSwitchProperties properties,
            OrderSettlementService javaService,
            LegacySettleOrderProcedureGateway legacyGateway) {
        this.properties = properties;
        this.javaService = javaService;
        this.legacyGateway = legacyGateway;
    }

    public SettlementOutcome settleOrder(int orderId) {
        if (isInRollout(orderId)) {
            return new SettlementOutcome(javaService.settleOrder(orderId), RouteTarget.JAVA);
        }
        SettlementResult result = legacyGateway.call(orderId);
        return new SettlementOutcome(result, RouteTarget.LEGACY_PROCEDURE);
    }

    /** 按 orderId 哈希取模,同一个 orderId 每次调用的路由结果稳定,不依赖随机数。 */
    private boolean isInRollout(int orderId) {
        if (!properties.isEnabled()) {
            return false;
        }
        int percentage = Math.clamp(properties.getRolloutPercentage(), 0, 100);
        return Math.floorMod(Integer.hashCode(orderId), 100) < percentage;
    }
}
