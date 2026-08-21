package com.example.migration.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * @MapperScan 单独放一个 @Configuration 类,不放在 MigrationApplication 上。
 * @MapperScan 是 ImportBeanDefinitionRegistrar,不受 @WebMvcTest 之类切片测试的组件类型过滤影响,
 * 放在主启动类上会导致 Web 层切片测试也尝试创建 Mapper bean,而切片测试里没有 DataSource,
 * 会报 "Property 'sqlSessionFactory' or 'sqlSessionTemplate' are required"。
 */
@Configuration
@MapperScan("com.example.migration.repository.mybatis")
public class MyBatisConfig {
}
