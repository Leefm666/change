package com.example.migration.routing;

import com.example.migration.domain.PayrollResult;
import com.example.migration.legacy.LegacyPayrollProcedureGateway;
import com.example.migration.routing.PayrollOutcome.RouteTarget;
import com.example.migration.service.PayrollService;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

/**
 * hr.hr_payroll 迁移的灰度路由入口:按 PayrollMigrationSwitchProperties 决定这次调用
 * 走新 Java 实现还是旧存储过程。不加 @Transactional——两条分支各自管理自己的事务边界
 * (PayrollService 内部 @Transactional;LegacyPayrollProcedureGateway 走独立 autocommit
 * 连接),路由本身不应该把二者并到同一个事务里。
 */
@Service
public class PayrollRoutingService {

    private final PayrollMigrationSwitchProperties properties;
    private final PayrollService javaService;
    private final LegacyPayrollProcedureGateway legacyGateway;

    public PayrollRoutingService(
            PayrollMigrationSwitchProperties properties,
            PayrollService javaService,
            LegacyPayrollProcedureGateway legacyGateway) {
        this.properties = properties;
        this.javaService = javaService;
        this.legacyGateway = legacyGateway;
    }

    public PayrollOutcome calculateAndApprovePayroll(
            int employeeId, int periodId, int approverId, BigDecimal bonusOverride) {
        if (isInRollout(employeeId, periodId)) {
            PayrollResult result =
                    javaService.calculateAndApprovePayroll(employeeId, periodId, approverId, bonusOverride);
            return new PayrollOutcome(result, RouteTarget.JAVA);
        }
        PayrollResult result = legacyGateway.call(employeeId, periodId, approverId, bonusOverride);
        return new PayrollOutcome(result, RouteTarget.LEGACY_PROCEDURE);
    }

    /**
     * 按"员工+核算周期"这个业务主键哈希取模,同一次核算请求每次调用的路由结果稳定,
     * 不依赖随机数。用组合键而不是单独 employeeId,避免同一员工的所有周期永远粘死在同一侧。
     */
    private boolean isInRollout(int employeeId, int periodId) {
        if (!properties.isEnabled()) {
            return false;
        }
        int percentage = Math.clamp(properties.getRolloutPercentage(), 0, 100);
        int businessKey = Long.hashCode((long) employeeId * 1_000_000L + periodId);
        return Math.floorMod(businessKey, 100) < percentage;
    }
}
