package com.levango7.dataenginebdp.ruleengine.service;

import com.levango7.dataenginebdp.ruleengine.engine.DqRuleExecutor;
import com.levango7.dataenginebdp.ruleengine.model.Rule;
import com.levango7.dataenginebdp.ruleengine.model.RuleExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据质量规则校验执行服务。
 *
 * <p>对 {@link Rule} 执行校验并缓存最近一次校验结果，
 * 供 {@link com.levango7.dataenginebdp.ruleengine.controller.QualityRuleController}
 * 的 {@code check} 与 {@code summary} 端点使用。</p>
 *
 * <p>校验执行分两条路径：
 * <ul>
 *   <li><b>SQL 规则</b>（expression 以 {@code sql:} 前缀开头）：委托给
 *       {@link DqRuleExecutor} 执行真实 SQL 校验，通过 JdbcTemplate 查询违规数据数量，
 *       {@code count=0} 表示 PASS，{@code count>0} 表示 FAIL。需配置数据源。</li>
 *   <li><b>非 SQL 规则</b>（expression 不以 {@code sql:} 开头）：走基于 expression/severity
 *       的降级判断逻辑（expression 缺失 → 不通过；threshold=100% 或 threshold=0 → 通过；
 *       severity=BLOCK 且未满足严格阈值 → 不通过；其他 → 通过）。无需数据源。</li>
 * </ul></p>
 */
@Service
public class QualityCheckExecutionService {

    private static final Logger log = LoggerFactory.getLogger(QualityCheckExecutionService.class);
    private static final String SQL_PREFIX = "sql:";

    /** DQ 规则执行器，用于执行真实 SQL 校验（构造器注入）。 */
    private final DqRuleExecutor dqRuleExecutor;

    /** 按 ruleId 缓存最近一次校验结果（线程安全）。 */
    private final Map<Long, CheckResult> results = new ConcurrentHashMap<>();

    /**
     * 构造器注入 {@link DqRuleExecutor}。
     *
     * @param dqRuleExecutor DQ 规则执行器
     */
    public QualityCheckExecutionService(DqRuleExecutor dqRuleExecutor) {
        this.dqRuleExecutor = dqRuleExecutor;
    }

    /**
     * 执行规则校验并缓存结果。
     *
     * @param rule 待校验规则（id 不能为 null）
     * @return 校验结果
     */
    public CheckResult executeCheck(Rule rule) {
        if (rule == null || rule.getId() == null) {
            throw new IllegalArgumentException("rule 与 rule.id 不能为 null");
        }

        Instant now = Instant.now();
        String expr = rule.getExpression();
        String severity = rule.getSeverity();

        boolean passed;
        String message;
        if (expr != null && !expr.isBlank() && expr.startsWith(SQL_PREFIX)) {
            // SQL 规则走真实执行：委托给 DqRuleExecutor 通过 JdbcTemplate 执行 SQL，
            // count=0 表示无违规数据 → PASS，count>0 表示发现违规 → FAIL。
            RuleExecutionResult execResult = dqRuleExecutor.execute(rule, Collections.emptyMap());
            passed = "PASS".equals(execResult.getStatus());
            message = "SQL 规则真实校验：" + execResult.getMessage();
            log.info("委托 DqRuleExecutor 执行 SQL 规则: ruleId={}, status={}, message={}",
                    rule.getId(), execResult.getStatus(), execResult.getMessage());
        } else if (expr == null || expr.isBlank()) {
            // 非 SQL 规则走表达式匹配降级处理
            passed = false;
            message = "缺少校验表达式";
        } else if (expr.contains("threshold=100%") || expr.contains("threshold=0")) {
            passed = true;
            message = "校验通过：达到严格阈值";
        } else if ("BLOCK".equalsIgnoreCase(severity)) {
            passed = false;
            message = "校验未通过：阻断级别未达标";
        } else {
            passed = true;
            message = "校验通过";
        }

        CheckResult result = new CheckResult(rule.getId(), passed, now, message);
        results.put(rule.getId(), result);
        log.info("质量规则校验完成: ruleId={}, passed={}, message={}", rule.getId(), passed, message);
        return result;
    }

    /**
     * 获取指定规则的最近校验结果。
     *
     * @param ruleId 规则 ID
     * @return 校验结果；若未执行过校验则返回 null
     */
    public CheckResult getCheckResult(Long ruleId) {
        if (ruleId == null) {
            return null;
        }
        return results.get(ruleId);
    }

    /**
     * 统计已校验规则中通过的规则数。
     *
     * @return 通过的规则数
     */
    public long getPassedCount() {
        return results.values().stream().filter(CheckResult::passed).count();
    }

    /**
     * 统计已校验规则总数。
     *
     * @return 已校验规则数
     */
    public int getTotalChecked() {
        return results.size();
    }

    /**
     * 清空所有校验结果（主要用于测试）。
     */
    public void clear() {
        results.clear();
    }

    /**
     * 单条规则校验结果。
     *
     * @param ruleId      规则 ID
     * @param passed      是否通过
     * @param lastCheckAt 校验时间
     * @param message     结果消息
     */
    public record CheckResult(Long ruleId, boolean passed, Instant lastCheckAt, String message) {
    }
}