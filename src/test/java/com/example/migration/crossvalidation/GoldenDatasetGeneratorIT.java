package com.example.migration.crossvalidation;

import com.example.migration.golden.GoldenDatasetFile;
import com.example.migration.golden.GoldenScenario;
import com.example.migration.golden.GoldenScenario.Outcome;
import com.example.migration.golden.GoldenScenarios;
import com.example.migration.support.InParam;
import com.example.migration.support.JdbcProcedureExecutionRecorder;
import com.example.migration.support.OutParamSpec;
import com.example.migration.support.ProcedureExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 生成 docs/golden/demo.settle_order.golden.json:对 GoldenScenarios.DEFINITIONS 里
 * 每个场景实际调用真实 openGauss 存储过程,把结果写成一份可版本控制、可人工审查的黄金数据集。
 *
 * ⚠️ 这是"生成/覆盖"动作,不是普通回归测试,故意不打 cross-validation 标签,
 * 不会被 `mvn verify -P cross-validation` 自动带出来跑。只有明确执行以下命令才会触发:
 *
 *   OG_TEST_PASSWORD=&lt;密码&gt; mvn verify -P golden-generate
 *
 * 生成后必须用 git diff 检查这份 JSON 的变化是否符合预期再决定是否提交——如果这份文件被
 * 静默覆盖成"错误的新答案"却没人审查,黄金数据集就失去了当基准的意义。
 * 生成后配套跑 GoldenDatasetComparisonIT(比对 Java 实现)和
 * GoldenDatasetStalenessCheckIT(下次跑 cross-validation 时探测文件是否又过期了)。
 */
@Tag("golden-generate")
class GoldenDatasetGeneratorIT {

    private static final Path GOLDEN_FILE = Path.of("docs/golden/demo.settle_order.golden.json");

    @Test
    void generate() throws Exception {
        String password = CrossValidationFixtures.requirePassword();
        DataSource dataSource = CrossValidationFixtures.buildDataSource(password);
        JdbcProcedureExecutionRecorder recorder = CrossValidationFixtures.buildLegacyProcedureRecorder(dataSource);

        List<GoldenScenario> scenarios = new ArrayList<>();
        for (GoldenScenario definition : GoldenScenarios.DEFINITIONS) {
            CrossValidationFixtures.resetAndSeed(dataSource);
            ProcedureExecutionResult result = callProcedure(recorder, definition.orderId());
            scenarios.add(toRecordedScenario(definition, result));
        }

        GoldenDatasetFile datasetFile = new GoldenDatasetFile(
                Instant.now().toString(), queryOpenGaussVersion(dataSource), scenarios);

        Files.createDirectories(GOLDEN_FILE.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(GOLDEN_FILE.toFile(), datasetFile);

        System.out.println("已(重新)生成黄金数据集: " + GOLDEN_FILE.toAbsolutePath());
        System.out.println("提交前请用 git diff 检查这份文件的变化是否符合预期,不要无审查地提交。");
    }

    private static ProcedureExecutionResult callProcedure(JdbcProcedureExecutionRecorder recorder, int orderId) {
        return recorder.invoke(
                "{call demo.settle_order(?, ?, ?)}",
                List.of(new InParam(1, orderId, Types.INTEGER)),
                List.of(
                        new OutParamSpec(2, "o_final_amount", Types.NUMERIC),
                        new OutParamSpec(3, "o_status", Types.VARCHAR)),
                List.of());
    }

    private static GoldenScenario toRecordedScenario(GoldenScenario definition, ProcedureExecutionResult result) {
        if (result.thrownError().isPresent()) {
            return new GoldenScenario(
                    definition.name(), definition.orderId(), definition.description(),
                    Outcome.FAILURE, null, null,
                    result.thrownError().get().sqlState(),
                    result.thrownError().get().message());
        }
        BigDecimal finalAmount = (BigDecimal) result.outParams().get("o_final_amount");
        String status = (String) result.outParams().get("o_status");
        return new GoldenScenario(
                definition.name(), definition.orderId(), definition.description(),
                Outcome.SUCCESS, finalAmount.toPlainString(), status, null, null);
    }

    private static String queryOpenGaussVersion(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select version()")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
