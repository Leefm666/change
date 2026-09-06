package com.example.migration.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * hr.hr_payroll 迁移的灰度开关配置,来自 application.yml 的 migration.hr-payroll.*
 * 或对应的环境变量(MIGRATION_HR_PAYROLL_ENABLED / MIGRATION_HR_PAYROLL_ROLLOUT_PERCENTAGE)。
 * 改值需要重启应用生效。
 */
@ConfigurationProperties(prefix = "migration.hr-payroll")
public class PayrollMigrationSwitchProperties {

    /** 总开关。false 时忽略 rolloutPercentage,全部走旧存储过程。 */
    private boolean enabled = false;

    /** 灰度百分比(0-100)。按业务主键哈希取模决定是否命中灰度。 */
    private int rolloutPercentage = 0;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRolloutPercentage() {
        return rolloutPercentage;
    }

    public void setRolloutPercentage(int rolloutPercentage) {
        this.rolloutPercentage = rolloutPercentage;
    }
}
