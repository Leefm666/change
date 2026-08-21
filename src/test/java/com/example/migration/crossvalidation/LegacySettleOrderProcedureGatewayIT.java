package com.example.migration.crossvalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.migration.domain.SettlementResult;
import com.example.migration.legacy.LegacyProcedureFailureException;
import com.example.migration.legacy.LegacySettleOrderProcedureGateway;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 验证 main 代码里新写的 LegacySettleOrderProcedureGateway(灰度关闭/未命中时实际调用真实
 * openGauss 存储过程的入口)行为正确:能正常结算、能把存储过程抛出的 SQLSTATE 正确转换成
 * LegacyProcedureFailureException。这是对 OrderSettlementCrossValidationIT 的补充——
 * 那边验证的是"Java 实现 vs 存储过程"一致,这里验证的是"新写的生产网关本身调用无误"。
 */
@Tag("cross-validation")
class LegacySettleOrderProcedureGatewayIT {

    private static DataSource dataSource;
    private static LegacySettleOrderProcedureGateway gateway;

    @BeforeAll
    static void setUp() throws Exception {
        String password = CrossValidationFixtures.requirePassword();
        dataSource = CrossValidationFixtures.buildDataSource(password);
        gateway = new LegacySettleOrderProcedureGateway(dataSource);
    }

    @BeforeEach
    void resetData() throws SQLException {
        CrossValidationFixtures.resetAndSeed(dataSource);
    }

    @Test
    void call_normalCustomer_settlesSuccessfully() {
        SettlementResult result = gateway.call(101);

        assertThat(result.finalAmount()).isEqualByComparingTo("200.00");
        assertThat(result.status()).isEqualTo("SETTLED");
    }

    @Test
    void call_vipCustomer_appliesDiscount() {
        SettlementResult result = gateway.call(102);

        assertThat(result.finalAmount()).isEqualByComparingTo("285.00");
    }

    @Test
    void call_stockShortage_throwsWithSqlStateP0003() {
        assertThatThrownBy(() -> gateway.call(103))
                .isInstanceOf(LegacyProcedureFailureException.class)
                .satisfies(e -> assertThat(((LegacyProcedureFailureException) e).sqlState()).isEqualTo("P0003"))
                .hasMessageContaining("商品库存不足");
    }

    @Test
    void call_insufficientBalance_throwsWithSqlStateP0004() {
        assertThatThrownBy(() -> gateway.call(104))
                .isInstanceOf(LegacyProcedureFailureException.class)
                .satisfies(e -> assertThat(((LegacyProcedureFailureException) e).sqlState()).isEqualTo("P0004"));
    }

    @Test
    void call_alreadySettled_throwsWithSqlStateP0002() {
        assertThatThrownBy(() -> gateway.call(105))
                .isInstanceOf(LegacyProcedureFailureException.class)
                .satisfies(e -> assertThat(((LegacyProcedureFailureException) e).sqlState()).isEqualTo("P0002"));
    }
}
