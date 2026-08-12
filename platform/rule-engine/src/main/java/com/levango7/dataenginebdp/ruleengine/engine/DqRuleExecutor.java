package com.levango7.dataenginebdp.ruleengine.engine;

import com.levango7.dataenginebdp.ruleengine.model.Rule;
import com.levango7.dataenginebdp.ruleengine.model.RuleExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据质量检查（DQ）规则执行器。
 *
 * <p>支持两种表达式模式：
 * <ul>
 *   <li><b>SQL 模式</b>：表达式以 {@code sql:} 前缀，后跟完整 SQL（应返回单行单列 count）。
 *       通过 {@link JdbcTemplate} 执行 SQL，{@code count=0} 表示 PASS（无违规数据），
 *       {@code count>0} 表示 FAIL（发现违规数据）。需配置数据源。</li>
 *   <li><b>条件模式</b>：表达式形如 {@code metric op threshold}（如 {@code nullCount > 0}），
 *       从 {@code context} 中读取 metric 值进行评估。条件不满足时 PASS，满足时 FAIL。
 *       无需数据源，纯内存评估。</li>
 * </ul>
 * 当未配置 {@link JdbcTemplate} 时，SQL 模式返回 {@code ERROR}（DATA_SOURCE_NOT_CONFIGURED）；
 * 条件模式不依赖数据源，始终可工作。表达式为空或无法解析时返回 PASS（视为无违规）。</p>
 */
@Component
public class DqRuleExecutor implements RuleExecutor {

    private static final Logger log = LoggerFactory.getLogger(DqRuleExecutor.class);
    private static final String SQL_PREFIX = "sql:";

    /** 数据源 JdbcTemplate，可选注入（单元测试或无数据源环境时为 null） */
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    /** Spring 注入用构造函数 */
    public DqRuleExecutor() {
    }

    /** 测试用构造函数，显式传入 JdbcTemplate */
    public DqRuleExecutor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getType() {
        return "DQ";
    }

    @Override
    public RuleExecutionResult execute(Rule rule, Map<String, Object> context) {
        long start = System.currentTimeMillis();
        String expression = rule.getExpression() == null ? "" : rule.getExpression();
        Map<String, Object> details = new HashMap<>();
        details.put("type", "DQ");
        details.put("expression", expression);

        try {
            if (expression.startsWith(SQL_PREFIX)) {
                return executeSqlMode(rule, expression, details, start);
            } else {
                return executeConditionMode(rule, expression, context, details, start);
            }
        } catch (Exception e) {
            log.error("DQ rule execution failed: ruleId={}, expr={}", rule.getId(), expression, e);
            details.put("error", e.getMessage());
            return buildResult(rule, "ERROR", "DQ_EXECUTION_ERROR: " + e.getMessage(),
                    details, start);
        }
    }

    /** SQL 模式：执行 SQL 查询违规数据数量 */
    private RuleExecutionResult executeSqlMode(Rule rule, String expression,
                                               Map<String, Object> details, long start) {
        String sql = expression.substring(SQL_PREFIX.length()).trim();
        if (sql.isEmpty()) {
            return buildResult(rule, "ERROR", "EMPTY_SQL", details, start);
        }
        if (jdbcTemplate == null) {
            log.warn("DQ SQL mode requires JdbcTemplate but none configured: ruleId={}", rule.getId());
            return buildResult(rule, "ERROR", "DATA_SOURCE_NOT_CONFIGURED", details, start);
        }
        Long count = jdbcTemplate.queryForObject(sql, Long.class);
        if (count == null) {
            count = 0L;
        }
        details.put("violationCount", count);
        boolean pass = count == 0L;
        log.info("DQ SQL check: ruleId={}, violationCount={}, pass={}", rule.getId(), count, pass);
        return buildResult(rule,
                pass ? "PASS" : "FAIL",
                pass ? "DQ_CHECK_PASSED" : "DQ_CHECK_FAILED",
                details, start);
    }

    /** 条件模式：从 context 评估条件表达式 */
    private RuleExecutionResult executeConditionMode(Rule rule, String expression,
                                                     Map<String, Object> context,
                                                     Map<String, Object> details, long start) {
        ConditionEvaluator.EvalResult er = ConditionEvaluator.evaluate(expression, context);
        details.put("evaluated", er.evaluated());
        details.put("triggered", er.triggered());
        if (er.evaluated()) {
            details.put("detail", er.detail());
        }
        // 条件触发表示发现违规 → FAIL；未触发或无法评估 → PASS
        boolean pass = !er.triggered();
        return buildResult(rule,
                pass ? "PASS" : "FAIL",
                pass ? "DQ_CHECK_PASSED" : "DQ_CHECK_FAILED",
                details, start);
    }

    private RuleExecutionResult buildResult(Rule rule, String status, String message,
                                            Map<String, Object> details, long start) {
        return RuleExecutionResult.builder()
                .ruleId(rule.getId())
                .status(status)
                .message(message)
                .details(details)
                .durationMs(System.currentTimeMillis() - start)
                .executedAt(LocalDateTime.now())
                .build();
    }
}
