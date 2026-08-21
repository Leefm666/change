package com.example.migration.crossvalidation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.migration.domain.SettlementResult;
import com.example.migration.service.OrderSettlementService;
import com.example.migration.support.InParam;
import com.example.migration.support.JdbcProcedureExecutionRecorder;
import com.example.migration.support.OutParamSpec;
import com.example.migration.support.ProcedureExecutionResult;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import javax.sql.DataSource;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * demo.settle_order 是 Tier 3(高风险核心,见 docs/rules/demo.settle_order.md),
 * 手写用例难以覆盖客户/商品/数量的全部组合。这里用 jqwik 随机采样"客户 x 商品 x 数量"
 * 组合,each try 新建一个订单,双跑旧存储过程与新 Java 实现并比较结果,作为手写用例之外
 * 的补充安全网(方案第5节)。不一致时 jqwik 会自动 shrink 到最小复现输入并报错失败。
 *
 * 运行方式与 OrderSettlementCrossValidationIT 相同: mvn verify -P cross-validation
 * (需先 docker compose up -d,并设置 OG_TEST_PASSWORD)。
 */
@Tag("cross-validation")
class OrderSettlementBatchRegressionIT {

    private static final int NEW_ORDER_ID = 90000;
    private static final int NEW_ITEM_ID = 90000;
    private static final String[] PRODUCT_CODES = {"SKU-001", "SKU-002", "SKU-003"};

    private static DataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static OrderSettlementService javaService;
    private static TransactionTemplate transactionTemplate;
    private static JdbcProcedureExecutionRecorder legacyProcedureRecorder;

    @BeforeContainer
    static void setUpDataSource() throws Exception {
        String password = CrossValidationFixtures.requirePassword();
        dataSource = CrossValidationFixtures.buildDataSource(password);
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        legacyProcedureRecorder = CrossValidationFixtures.buildLegacyProcedureRecorder(dataSource);
        javaService = CrossValidationFixtures.buildJavaService(dataSource);
    }

    @BeforeProperty
    void resetData() throws SQLException {
        CrossValidationFixtures.resetAndSeed(dataSource);
    }

    /**
     * 随机组合:customerId(1=普通/2=VIP/3=SVIP/4=低余额)x 商品下标 x 数量,
     * 每次生成一个全新订单(id=NEW_ORDER_ID),双跑旧存储过程与新 Java 实现并比较。
     */
    @Property(tries = 50)
    void settleOrder_randomCombination_javaAndProcedureAgree(
            @ForAll @IntRange(min = 1, max = 4) int customerId,
            @ForAll @IntRange(min = 0, max = 2) int productIndex,
            @ForAll @IntRange(min = 1, max = 20) int quantity) throws SQLException {

        resetData();
        String productCode = PRODUCT_CODES[productIndex];
        insertRandomOrder(customerId, productCode, quantity);

        ProcedureExecutionResult procedureResult = callLegacyProcedure(NEW_ORDER_ID);
        boolean procedureSucceeded = procedureResult.thrownError().isEmpty();

        resetData();
        insertRandomOrder(customerId, productCode, quantity);

        SettlementResult javaResult = null;
        boolean javaSucceeded;
        try {
            javaResult = transactionTemplate.execute(status -> javaService.settleOrder(NEW_ORDER_ID));
            javaSucceeded = true;
        } catch (RuntimeException e) {
            javaSucceeded = false;
        }

        assertThat(javaSucceeded)
                .as("customerId=%d productCode=%s quantity=%d: 存储过程与 Java 实现的成功/失败结果应一致",
                        customerId, productCode, quantity)
                .isEqualTo(procedureSucceeded);

        if (procedureSucceeded) {
            BigDecimal procedureAmount = (BigDecimal) procedureResult.outParams().get("o_final_amount");
            assertThat(javaResult.finalAmount())
                    .as("customerId=%d productCode=%s quantity=%d: 结算金额应一致",
                            customerId, productCode, quantity)
                    .isEqualByComparingTo(procedureAmount);
        }
    }

    private void insertRandomOrder(int customerId, String productCode, int quantity) {
        jdbcTemplate.update(
                "INSERT INTO demo.orders(order_id, customer_id, status, total_amount) "
                        + "VALUES (?, ?, 'PENDING', ?)",
                NEW_ORDER_ID, customerId, new BigDecimal(quantity * 10));
        jdbcTemplate.update(
                "INSERT INTO demo.order_items(item_id, order_id, product_code, quantity, unit_price) "
                        + "VALUES (?, ?, ?, ?, 10.00)",
                NEW_ITEM_ID, NEW_ORDER_ID, productCode, quantity);
    }

    private ProcedureExecutionResult callLegacyProcedure(int orderId) {
        return legacyProcedureRecorder.invoke(
                "{call demo.settle_order(?, ?, ?)}",
                List.of(new InParam(1, orderId, Types.INTEGER)),
                List.of(
                        new OutParamSpec(2, "o_final_amount", Types.NUMERIC),
                        new OutParamSpec(3, "o_status", Types.VARCHAR)),
                List.of());
    }
}
