package com.example.migration.web;

import com.example.migration.routing.PayrollRoutingService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * ⚠️ 未接入鉴权:这个接口会真实写入薪资审批记录,任何能访问到这个端口的调用方都能触发核算。
 * 当前仅用于验证 hr.hr_payroll 的灰度路由机制(见 PayrollRoutingService),
 * 上线前必须在网关层或本类补上认证/授权,不能就这样暴露。
 */
@RestController
public class PayrollController {

    private final PayrollRoutingService routingService;

    public PayrollController(PayrollRoutingService routingService) {
        this.routingService = routingService;
    }

    @PostMapping("/api/payroll/calculate-and-approve")
    public PayrollResponse calculateAndApprove(@RequestBody PayrollRequest request) {
        var outcome = routingService.calculateAndApprovePayroll(
                request.employeeId(), request.periodId(), request.approverId(), request.bonusOverride());
        return PayrollResponse.from(outcome);
    }
}
