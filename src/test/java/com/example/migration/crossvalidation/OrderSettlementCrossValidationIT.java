package com.example.migration.crossvalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.migration.domain.SettlementResult;
import com.example.migration.service.OrderSettlementService;
import com.example.migration.support.InParam;
import com.example.migration.support.JdbcProcedureExecutionRecorder;
import com.example.migration.support.OutParamSpec;
import com.example.migration.support.ProcedureExecutionResult;
import com.example.migration.support.TableSnapshotSpec;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 交叉验证:用同一批种子数据,分别调用真实 openGauss 存储过程 demo.settle_order
 * 和其 Java 等价实现 OrderSettlementService,比较结果与表状态是否一致。
 *
 * 依赖本地已启动的 docker-compose(见项目根 docker-compose.yml)openGauss 实例,
 * 端口 15432,通过环境变量 OG_TEST_PASSWORD 传入密码。
 * 运行方式: mvn verify -P cross-validation (需先 docker compose up -d)
 */
@Tag("cross-validation")
class OrderSettlementCrossValidationIT {

    /** 交叉验证要 diff 的表,主键列用于按行对齐比较调用前后的快照。 */
    private static final List<TableSnapshotSpec> TABLES_TO_DIFF = List.of(
            new TableSnapshotSpec("demo.customers", List.of("customer_id")),
            new TableSnapshotSpec("demo.orders", List.of("order_id")),
            new TableSnapshotSpec("demo.order_items", List.of("item_id")),
            new TableSnapshotSpec("demo.inventory", List.of("product_code")),
            new TableSnapshotSpec("demo.settlement_log", List.of("order_id", "event")));

    private static DataSource dataSource;
    private static OrderSettlementService javaService;
    private static TransactionTemplate transactionTemplate;
    private static JdbcProcedureExecutionRecorder legacyProcedureRecorder;

    @BeforeAll
    static void setUpDataSource() throws Exception {
        String password = CrossValidationFixtures.requirePassword();
        dataSource = CrossValidationFixtures.buildDataSource(password);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        legacyProcedureRecorder = CrossValidationFixtures.buildLegacyProcedureRecorder(dataSource);
        javaService = CrossValidationFixtures.buildJavaService(dataSource);
    }

    @BeforeEach
    void resetData() throws SQLException {
        CrossValidationFixtures.resetAndSeed(dataSource);
    }

    @Test
    void settleOrder_normalCustomer_javaAndProcedureAgree() {
        ProcedureExecutionResult procedureResult = callLegacyProcedure(101);
        resetDataQuietly();
        SettlementResult javaResult = settleOrderInTransaction(101);

        assertThat(procedureResult.thrownError()).isEmpty();
        assertThat(javaResult.finalAmount()).isEqualByComparingTo((BigDecimal) procedureResult.outParams().get("o_final_amount"));
        assertThat(javaResult.finalAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void settleOrder_vipCustomer_javaAndProcedureAgree() {
        ProcedureExecutionResult procedureResult = callLegacyProcedure(102);
        resetDataQuietly();
        SettlementResult javaResult = settleOrderInTransaction(102);

        assertThat(procedureResult.thrownError()).isEmpty();
        assertThat(javaResult.finalAmount()).isEqualByComparingTo((BigDecimal) procedureResult.outParams().get("o_final_amount"));
        assertThat(javaResult.finalAmount()).isEqualByComparingTo("285.00");
    }

    @Test
    void settleOrder_stockShortage_bothThrowAndLeaveTablesUnchanged() {
        ProcedureExecutionResult procedureResult = callLegacyProcedure(103);
        assertThat(procedureResult.thrownError()).isPresent();
        assertThat(procedureResult.thrownError().get().message()).contains("商品库存不足");
        // 存储过程的 EXCEPTION 块回滚了本次调用的全部写操作,调用前后表快照应完全一致。
        assertThat(procedureResult.tableDiffs()).allSatisfy(diff -> assertThat(diff.isEmpty()).isTrue());

        resetDataQuietly();

        assertThatThrownBy(() -> settleOrderInTransaction(103))
                .hasMessageContaining("商品库存不足");
    }

    @Test
    void settleOrder_insufficientBalance_bothThrow() {
        ProcedureExecutionResult procedureResult = callLegacyProcedure(104);
        assertThat(procedureResult.thrownError()).isPresent();
        assertThat(procedureResult.thrownError().get().message()).contains("客户余额不足");

        resetDataQuietly();

        assertThatThrownBy(() -> settleOrderInTransaction(104))
                .hasMessageContaining("客户余额不足");
    }

    @Test
    void settleOrder_alreadySettled_bothThrow() {
        ProcedureExecutionResult procedureResult = callLegacyProcedure(105);
        assertThat(procedureResult.thrownError()).isPresent();
        assertThat(procedureResult.thrownError().get().message()).contains("订单状态不合法");

        resetDataQuietly();

        assertThatThrownBy(() -> settleOrderInTransaction(105))
                .hasMessageContaining("订单状态不合法");
    }

    @Test
    void settleOrder_multipleItems_javaAndProcedureAgree() {
        ProcedureExecutionResult procedureResult = callLegacyProcedure(106);
        resetDataQuietly();
        SettlementResult javaResult = settleOrderInTransaction(106);

        assertThat(procedureResult.thrownError()).isEmpty();
        assertThat(javaResult.finalAmount()).isEqualByComparingTo((BigDecimal) procedureResult.outParams().get("o_final_amount"));
    }

    /**
     * javaService 是直接 new 出来的,没有经过 Spring AOP 代理,@Transactional 注解本身不生效。
     * 这里用真实的 TransactionTemplate 包一层,复现失败路径下 settlement_log 插入被回滚的效果,
     * 否则交叉验证会得出"Java 侧失败路径日志被持久化"的假结论,与真实存储过程行为不符。
     */
    private SettlementResult settleOrderInTransaction(int orderId) {
        return transactionTemplate.execute(status -> javaService.settleOrder(orderId));
    }

    /** callLegacyProcedure 已经在自己的连接里跑完并 commit/rollback,这里只是重建数据基线,吞掉 SQLException 转成运行时异常。 */
    private void resetDataQuietly() {
        try {
            CrossValidationFixtures.resetAndSeed(dataSource);
        } catch (SQLException e) {
            throw new IllegalStateException("重置测试数据失败", e);
        }
    }

    private ProcedureExecutionResult callLegacyProcedure(int orderId) {
        return legacyProcedureRecorder.invoke(
                "{call demo.settle_order(?, ?, ?)}",
                List.of(new InParam(1, orderId, Types.INTEGER)),
                List.of(
                        new OutParamSpec(2, "o_final_amount", Types.NUMERIC),
                        new OutParamSpec(3, "o_status", Types.VARCHAR)),
                TABLES_TO_DIFF);
    }
}
