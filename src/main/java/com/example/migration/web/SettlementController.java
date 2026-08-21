package com.example.migration.web;

import com.example.migration.routing.SettlementOutcome;
import com.example.migration.routing.SettlementRoutingService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ⚠️ 未接入鉴权:这个接口会真实扣库存/扣客户余额,任何能访问到这个端口的调用方都能触发结算。
 * 当前仅用于验证 demo.settle_order 的灰度路由机制(见 SettlementRoutingService),
 * 上线前必须在网关层或本类补上认证/授权,不能就这样暴露。
 */
@RestController
public class SettlementController {

    private final SettlementRoutingService routingService;

    public SettlementController(SettlementRoutingService routingService) {
        this.routingService = routingService;
    }

    @PostMapping("/api/orders/{orderId}/settlement")
    public SettlementResponse settle(@PathVariable int orderId) {
        SettlementOutcome outcome = routingService.settleOrder(orderId);
        return SettlementResponse.from(outcome);
    }
}
