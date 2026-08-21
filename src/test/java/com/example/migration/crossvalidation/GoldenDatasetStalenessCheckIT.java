package com.example.migration.crossvalidation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.migration.golden.GoldenDatasetFile;
import com.example.migration.golden.GoldenDatasetLoader;
import com.example.migration.golden.GoldenScenario;
import com.example.migration.golden.GoldenScenario.Outcome;
import com.example.migration.support.InParam;
import com.example.migration.support.JdbcProcedureExecutionRecorder;
import com.example.migration.support.OutParamSpec;
import com.example.migration.support.ProcedureExecutionResult;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/**
 * 这是黄金数据集"过期哨兵":每次跑 cross-validation 都会执行,重新实时调用真实存储过程,
 * 检查结果是否还和 docs/golden/demo.settle_order.golden.json 里存的一致。
 *
 * 静态黄金文件最大的风险是"生成后没人记得更新"——如果存储过程本身后来打了合规的补丁改了行为,
 * 黄金文件却没跟着重新生成,GoldenDatasetComparisonIT 会一直拿着过期答案给 Java 实现"背书",
 * 造成虚假的安全感。这个类专门负责在每次交叉验证时戳破这种虚假安全感:一旦真实存储过程的
 * 行为跟文件不一致,这里就会失败并提示重新生成。
 *
 * 失败时的处理:先确认这是"存储过程行为的合规变更"还是"环境/数据问题"(比如种子数据被改过、
 * 连了错误的库),确认是合规变更后执行
 *   OG_TEST_PASSWORD=&lt;密码&gt; mvn verify -P golden-generate
 * 重新生成文件,提交前用 git diff 审查这份文件的变化是否符合预期。
 */
@Tag("cross-validation")
class GoldenDatasetStalenessCheckIT {

    private static DataSource dataSource;
    private static JdbcProcedureExecutionRecorder recorder;

    @BeforeAll
    static void setUp() throws Exception {
        String password = CrossValidationFixtures.requirePassword();
        dataSource = CrossValidationFixtures.buildDataSource(password);
        recorder = CrossValidationFixtures.buildLegacyProcedureRecorder(dataSource);
    }

    @BeforeEach
    void resetData() throws SQLException {
        CrossValidationFixtures.resetAndSeed(dataSource);
    }

    @TestFactory
    Iterable<DynamicTest> checkEachGoldenScenarioStillMatchesRealProcedure() {
        GoldenDatasetFile dataset = GoldenDatasetLoader.load();
        return dataset.scenarios().stream()
                .map(scenario -> DynamicTest.dynamicTest(scenario.name(), () -> verifyStillFresh(scenario)))
                .toList();
    }

    private void verifyStillFresh(GoldenScenario scenario) throws SQLException {
        resetData();
        ProcedureExecutionResult actual = recorder.invoke(
                "{call demo.settle_order(?, ?, ?)}",
                List.of(new InParam(1, scenario.orderId(), Types.INTEGER)),
                List.of(
                        new OutParamSpec(2, "o_final_amount", Types.NUMERIC),
                        new OutParamSpec(3, "o_status", Types.VARCHAR)),
                List.of());

        String staleFileHint = "黄金数据集看起来过期了(场景 " + scenario.name()
                + "),存储过程真实行为已经和 docs/golden/demo.settle_order.golden.json 不一致。"
                + "确认是合规的行为变更后执行: mvn verify -P golden-generate 重新生成,并 git diff 审查。";

        if (scenario.expectedOutcome() == Outcome.SUCCESS) {
            assertThat(actual.thrownError()).as(staleFileHint).isEmpty();
            BigDecimal actualAmount = (BigDecimal) actual.outParams().get("o_final_amount");
            assertThat(actualAmount).as(staleFileHint)
                    .isEqualByComparingTo(new BigDecimal(scenario.expectedFinalAmount()));
        } else {
            assertThat(actual.thrownError()).as(staleFileHint).isPresent();
            assertThat(actual.thrownError().get().sqlState())
                    .as(staleFileHint)
                    .isEqualTo(scenario.expectedSqlState());
        }
    }
}
