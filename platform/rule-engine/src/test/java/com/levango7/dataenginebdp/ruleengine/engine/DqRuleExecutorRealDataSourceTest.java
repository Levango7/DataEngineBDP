package com.levango7.dataenginebdp.ruleengine.engine;

import com.levango7.dataenginebdp.ruleengine.model.Rule;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DqRuleExecutor 真实数据源集成测试（H2 内存 + JdbcTemplate）。
 *
 * <p>验证 SQL 模式规则通过真实 JDBC 数据源执行（非 mock）：
 * 违规数据 count>0 → FAIL；无违规 → PASS；未配置数据源 → ERROR。</p>
 */
class DqRuleExecutorRealDataSourceTest {

    private JdbcTemplate newJdbc(String... sql) {
        // 唯一内存库名，避免跨测试共享(表已存在)
        EmbeddedDatabase db = new EmbeddedDatabaseBuilder()
                .setName("dqtest" + System.nanoTime())
                .setType(EmbeddedDatabaseType.H2)
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(db);
        for (String s : sql) {
            jdbc.execute(s);
        }
        return jdbc;
    }

    private Map<String, Object> context() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("table", "orders");
        return ctx;
    }

    @Test
    void sqlMode_findsViolationsViaRealJdbc() {
        // 建表 + 插违规数据（amount < 0）
        JdbcTemplate jdbc = newJdbc(
                "CREATE TABLE orders (id INT, amount DECIMAL(10,2))",
                "INSERT INTO orders VALUES (1, 100.00), (2, -5.00)");
        DqRuleExecutor executor = new DqRuleExecutor(jdbc);

        Rule rule = new Rule();
        rule.setType("QUALITY_RANGE");
        rule.setExpression("sql:SELECT COUNT(*) FROM orders WHERE amount < 0");

        var result = executor.execute(rule, context());

        assertThat(result.getStatus()).isEqualTo("FAIL");
        assertThat(result.getDetails()).containsEntry("violationCount", 1L);
    }

    @Test
    void sqlMode_passesWhenNoViolation() {
        JdbcTemplate jdbc = newJdbc(
                "CREATE TABLE orders (id INT, amount DECIMAL(10,2))",
                "INSERT INTO orders VALUES (1, 100.00), (2, 50.00)");
        DqRuleExecutor executor = new DqRuleExecutor(jdbc);

        Rule rule = new Rule();
        rule.setType("QUALITY_RANGE");
        rule.setExpression("sql:SELECT COUNT(*) FROM orders WHERE amount < 0");

        var result = executor.execute(rule, context());
        assertThat(result.getStatus()).isEqualTo("PASS");
        assertThat(result.getDetails()).containsEntry("violationCount", 0L);
    }

    @Test
    void sqlMode_withoutDataSource_returnsExplicitError() {
        // 无 JdbcTemplate → 显式 ERROR（DATA_SOURCE_NOT_CONFIGURED），非静默
        DqRuleExecutor executor = new DqRuleExecutor(null);

        Rule rule = new Rule();
        rule.setType("QUALITY_RANGE");
        rule.setExpression("sql:SELECT COUNT(*) FROM orders");

        var result = executor.execute(rule, context());
        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getMessage()).contains("DATA_SOURCE_NOT_CONFIGURED");
    }
}
