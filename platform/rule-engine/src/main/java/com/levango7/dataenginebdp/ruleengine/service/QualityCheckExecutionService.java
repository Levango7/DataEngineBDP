package com.levango7.dataenginebdp.ruleengine.service;

import com.levango7.dataenginebdp.ruleengine.model.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据质量规则校验执行服务。
 *
 * <p>对 {@link Rule} 执行校验并缓存最近一次校验结果，
 * 供 {@link com.levango7.dataenginebdp.ruleengine.controller.QualityRuleController}
 * 的 {@code check} 与 {@code summary} 端点使用。</p>
 *
 * <p>MVP 阶段采用基于规则 expression/severity 的模拟判断逻辑：
 * <ul>
 *   <li>expression 缺失 → 不通过（缺少校验表达式）</li>
 *   <li>threshold=100% 或 threshold=0 → 通过（严格阈值已满足）</li>
 *   <li>severity=BLOCK 且未满足严格阈值 → 不通过（阻断级别未达标）</li>
 *   <li>其他场景 → 通过</li>
 * </ul>
 * 后续可替换为真实规则执行引擎调用（参见 {@link RuleExecutionService}）。</p>
 */
@Service
public class QualityCheckExecutionService {

    private static final Logger log = LoggerFactory.getLogger(QualityCheckExecutionService.class);

    /** 按 ruleId 缓存最近一次校验结果（线程安全）。 */
    private final Map<Long, CheckResult> results = new ConcurrentHashMap<>();

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
        if (expr == null || expr.isBlank()) {
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