package com.example.migration;

import com.example.migration.routing.MigrationSwitchProperties;
import com.example.migration.routing.PayrollMigrationSwitchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({MigrationSwitchProperties.class, PayrollMigrationSwitchProperties.class})
public class MigrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(MigrationApplication.class, args);
    }
}
