package com.example.migration.crossvalidation;

import com.example.migration.repository.mybatis.CustomerMapper;
import com.example.migration.repository.mybatis.InventoryMapper;
import com.example.migration.repository.mybatis.MyBatisCustomerRepository;
import com.example.migration.repository.mybatis.MyBatisInventoryRepository;
import com.example.migration.repository.mybatis.MyBatisOrderItemRepository;
import com.example.migration.repository.mybatis.MyBatisOrderRepository;
import com.example.migration.repository.mybatis.MyBatisSettlementLogRepository;
import com.example.migration.repository.mybatis.OrderItemMapper;
import com.example.migration.repository.mybatis.OrderMapper;
import com.example.migration.repository.mybatis.SettlementLogMapper;
import com.example.migration.service.OrderSettlementService;
import com.example.migration.support.JdbcProcedureExecutionRecorder;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * 交叉验证测试共用的搭建逻辑,被 OrderSettlementCrossValidationIT 和
 * OrderSettlementBatchRegressionIT 两个测试类复用,避免重复。
 */
final class CrossValidationFixtures {

    static final String URL = "jdbc:opengauss://127.0.0.1:15432/postgres";
    // openGauss 禁止初始超级用户(opengauss)通过端口映射产生的"远程连接"登录,
    // 交叉验证测试需要用专门创建的业务用户连接,见 db/testdata/README.md 里的建库说明。
    static final String USER = "migration_test";

    private CrossValidationFixtures() {
    }

    static String requirePassword() {
        String password = System.getenv("OG_TEST_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "交叉验证测试需要环境变量 OG_TEST_PASSWORD(openGauss migration_test 用户密码),未设置");
        }
        return password;
    }

    static DataSource buildDataSource(String password) {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.opengauss.Driver.class);
        ds.setUrl(URL);
        ds.setUsername(USER);
        ds.setPassword(password);
        return ds;
    }

    /** 用 SpringManagedTransactionFactory,让 MyBatis 连接托管给外层 TransactionTemplate/@Transactional 的事务。 */
    static OrderSettlementService buildJavaService(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setTransactionFactory(new SpringManagedTransactionFactory());
        SqlSessionFactory sqlSessionFactory = factoryBean.getObject();
        // 手工搭建时没有 @MapperScan 帮忙注册,注解式 Mapper(无 XML)需要显式 addMapper 才能被识别。
        sqlSessionFactory.getConfiguration().addMapper(OrderMapper.class);
        sqlSessionFactory.getConfiguration().addMapper(CustomerMapper.class);
        sqlSessionFactory.getConfiguration().addMapper(OrderItemMapper.class);
        sqlSessionFactory.getConfiguration().addMapper(InventoryMapper.class);
        sqlSessionFactory.getConfiguration().addMapper(SettlementLogMapper.class);
        SqlSessionTemplate sqlSessionTemplate = new SqlSessionTemplate(sqlSessionFactory);

        return new OrderSettlementService(
                new MyBatisOrderRepository(sqlSessionTemplate.getMapper(OrderMapper.class)),
                new MyBatisCustomerRepository(sqlSessionTemplate.getMapper(CustomerMapper.class)),
                new MyBatisOrderItemRepository(sqlSessionTemplate.getMapper(OrderItemMapper.class)),
                new MyBatisInventoryRepository(sqlSessionTemplate.getMapper(InventoryMapper.class)),
                new MyBatisSettlementLogRepository(sqlSessionTemplate.getMapper(SettlementLogMapper.class)));
    }

    static JdbcProcedureExecutionRecorder buildLegacyProcedureRecorder(DataSource dataSource) {
        return new JdbcProcedureExecutionRecorder(() -> {
            try {
                return dataSource.getConnection();
            } catch (SQLException e) {
                throw new IllegalStateException("无法获取 openGauss 连接", e);
            }
        });
    }

    static void resetAndSeed(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(readFile("db/testdata/demo.order_settlement.reset.sql"));
            statement.execute(readFile("db/testdata/demo.order_settlement.seed.sql"));
        }
    }

    private static String readFile(String relativePath) {
        try {
            return Files.readString(java.nio.file.Path.of(relativePath));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取种子脚本: " + relativePath, e);
        }
    }
}
