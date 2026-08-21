package com.example.migration.crossvalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.migration.domain.SettlementResult;
import com.example.migration.golden.GoldenDatasetFile;
import com.example.migration.golden.GoldenDatasetLoader;
import com.example.migration.golden.GoldenScenario;
import com.example.migration.service.OrderSettlementService;
import java.math.BigDecimal;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 拿 docs/golden/demo.settle_order.golden.json(提前生成、可版本控制的黄金数据集)去校验
 * Java 实现,而不是每次都实时调用真实存储过程——这是用户要求的"先造数据覆盖分支生成黄金测试集,
 * 再拿 Java 实现去对比"那条路径的落地。
 *
 * 和 OrderSettlementCrossValidationIT 的区别:那边每次跑测试都实时调用真实存储过程当基准,
 * 天然不会有"基准过期"的问题,但看不到一份独立、可审查的期望值清单;这里的基准是静态文件,
 * 可读性更好、跑起来更快,但需要 GoldenDatasetStalenessCheckIT 定期确认这份文件没有过期
 * (存储过程真实行为已经变了,文件却没有跟着重新生成)。两套测试互相补足,不是二选一。
 */
@Tag("cross-validation")
class GoldenDatasetComparisonIT {

    private static DataSource dataSource;
    private static OrderSettlementService javaService;
    private static TransactionTemplate transactionTemplate;

    @BeforeAll
    static void setUp() throws Exception {
        String password = CrossValidationFixtures.requirePassword();
        dataSource = CrossValidationFixtures.buildDataSource(password);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        javaService = CrossValidationFixtures.buildJavaService(dataSource);
    }

    @BeforeEach
    void resetData() throws SQLException {
        CrossValidationFixtures.resetAndSeed(dataSource);
    }

    /**
     * 用 @TestFactory 而不是逐条手写 @Test:黄金文件里加场景时,这里自动多跑一条用例,
     * 不需要每次同步改测试代码。
     */
    @TestFactory
    Iterable<DynamicTest> compareEachGoldenScenario() {
        GoldenDatasetFile dataset = GoldenDatasetLoader.load();
        return dataset.scenarios().stream()
                .map(scenario -> DynamicTest.dynamicTest(scenario.name(), () -> verifyScenario(scenario)))
                .toList();
    }

    private void verifyScenario(GoldenScenario scenario) throws SQLException {
        resetData();

        if (scenario.expectedOutcome() == GoldenScenario.Outcome.SUCCESS) {
            SettlementResult result = transactionTemplate.execute(
                    status -> javaService.settleOrder(scenario.orderId()));

            assertThat(result.finalAmount())
                    .as("场景 %s (orderId=%d): 结算金额应匹配黄金数据集", scenario.name(), scenario.orderId())
                    .isEqualByComparingTo(new BigDecimal(scenario.expectedFinalAmount()));
            assertThat(result.status())
                    .as("场景 %s: 状态应匹配黄金数据集", scenario.name())
                    .isEqualTo(scenario.expectedStatus());
        } else {
            assertThatThrownBy(() -> transactionTemplate.execute(
                    status -> javaService.settleOrder(scenario.orderId())))
                    .as("场景 %s (orderId=%d): 应抛出与黄金数据集一致的异常", scenario.name(), scenario.orderId())
                    .hasMessageContaining(
                            scenario.expectedErrorMessageContains() == null
                                    ? ""
                                    : extractCoreMessage(scenario));
        }
    }

    /** 黄金文件里存的是存储过程原生报错文本(可能带连接前缀),这里只取冒号后的核心业务消息去匹配 Java 异常。 */
    private static String extractCoreMessage(GoldenScenario scenario) {
        String raw = scenario.expectedErrorMessageContains();
        int marker = raw.indexOf("ERROR:");
        String core = marker >= 0 ? raw.substring(marker + "ERROR:".length()).trim() : raw.trim();
        return core;
    }
}
