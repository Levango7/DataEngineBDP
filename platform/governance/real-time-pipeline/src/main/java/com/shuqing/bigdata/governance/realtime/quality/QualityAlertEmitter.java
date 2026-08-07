package com.shuqing.bigdata.governance.realtime.quality;

import com.shuqing.bigdata.governance.realtime.model.QualityAlert;
import com.shuqing.bigdata.governance.realtime.model.QualityRuleResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 质量违规告警发射器。
 *
 * <p>当 {@link QualityRuleResult} 评估结果为 FAIL 时，构造 {@link QualityAlert}
 * 并发送到告警渠道。目标告警延迟 ≤ 5s（从违规数据产生到告警发出）。
 *
 * <p>告警渠道（与平台 X3 统一运维观测对齐）：
 * <ul>
 *   <li><b>Webhook</b>：POST 到配置的告警 URL（默认开启）</li>
 *   <li><b>内存队列</b>：写入 {@code alertBuffer}，供 REST 端点查询（测试与降级用）</li>
 *   <li><b>日志</b>：WARN 级别输出，由日志采集链路收集到 Loki/ELK</li>
 * </ul>
 */
@Component
public class QualityAlertEmitter {

    private static final Logger log = LoggerFactory.getLogger(QualityAlertEmitter.class);

    private final RestClient webhookClient;
    private final String webhookUrl;
    private final boolean webhookEnabled;
    private final Counter alertEmittedCounter;
    private final Timer alertLatencyTimer;

    /** 告警内存缓冲（供 REST 查询与测试断言） */
    private final CopyOnWriteArrayList<QualityAlert> alertBuffer = new CopyOnWriteArrayList<>();

    /** 告警统计：按 severity 分组 */
    private final ConcurrentHashMap<String, Long> severityStats = new ConcurrentHashMap<>();

    @Autowired
    public QualityAlertEmitter(
            @Value("${governance.alert.webhook-url:http://localhost:9093/api/v2/alerts}") String webhookUrl,
            @Value("${governance.alert.webhook-enabled:true}") boolean webhookEnabled,
            MeterRegistry meterRegistry) {
        this.webhookUrl = webhookUrl;
        this.webhookEnabled = webhookEnabled;
        this.webhookClient = RestClient.builder()
                .baseUrl(webhookUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
        this.alertEmittedCounter = Counter.builder("governance.alert.emitted")
                .description("发出的质量违规告警数")
                .register(meterRegistry);
        this.alertLatencyTimer = Timer.builder("governance.alert.latency")
                .description("告警延迟（从违规数据产生到告警发出）")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        log.info("QualityAlertEmitter initialized: webhookUrl={}, enabled={}", webhookUrl, webhookEnabled);
    }

    /** 测试用构造函数（无 MeterRegistry） */
    public QualityAlertEmitter(String webhookUrl, boolean webhookEnabled) {
        this.webhookUrl = webhookUrl;
        this.webhookEnabled = webhookEnabled;
        this.webhookClient = RestClient.builder().baseUrl(webhookUrl).build();
        this.alertEmittedCounter = null;
        this.alertLatencyTimer = null;
    }

    /**
     * 发射质量违规告警。
     *
     * @param result 评估结果（必须为 FAIL）
     * @param violationTimestamp 违规数据产生时间戳
     * @param pipelineStartTimestamp 治理闭环开始时间戳（Catalog commit 时刻）
     * @return 构造的告警对象；发送失败时仍返回（已写入内存缓冲）
     */
    public QualityAlert emit(QualityRuleResult result, Instant violationTimestamp,
                             Instant pipelineStartTimestamp) {
        if (!result.isViolation()) {
            log.debug("Not a violation, skip alert: ruleId={}", result.getRuleId());
            return null;
        }

        Instant now = Instant.now();
        long alertLatencyMs = Duration.between(violationTimestamp, now).toMillis();
        long pipelineLatencyMs = Duration.between(pipelineStartTimestamp, now).toMillis();

        QualityAlert alert = QualityAlert.builder()
                .alertId(java.util.UUID.randomUUID().toString())
                .ruleId(result.getRuleId())
                .ruleType(result.getRuleType())
                .severity(determineSeverity(result))
                .tableIdentifier(result.getTableIdentifier())
                .fieldName(result.getFieldName())
                .violationValue(result.getViolationValue())
                .recordId(result.getRecordId())
                .message(buildAlertMessage(result))
                .violationTimestamp(violationTimestamp)
                .alertTimestamp(now)
                .alertLatencyMs(alertLatencyMs)
                .pipelineLatencyMs(pipelineLatencyMs)
                .build();

        // 写入内存缓冲
        alertBuffer.add(alert);
        severityStats.merge(alert.getSeverity(), 1L, Long::sum);

        // 发送到 webhook
        if (webhookEnabled) {
            sendWebhook(alert);
        }

        // 记录指标
        if (alertEmittedCounter != null) {
            alertEmittedCounter.increment();
        }
        if (alertLatencyTimer != null) {
            alertLatencyTimer.record(Duration.ofMillis(alertLatencyMs));
        }

        log.warn("Quality alert emitted: ruleId={}, table={}, field={}, severity={}, " +
                        "alertLatency={}ms, pipelineLatency={}ms",
                alert.getRuleId(), alert.getTableIdentifier(), alert.getFieldName(),
                alert.getSeverity(), alertLatencyMs, pipelineLatencyMs);
        return alert;
    }

    /**
     * 获取告警缓冲（供 REST 查询与测试断言）。
     *
     * @return 不可变的告警列表
     */
    public List<QualityAlert> getAlertBuffer() {
        return List.copyOf(alertBuffer);
    }

    /**
     * 获取指定表最近的告警。
     *
     * @param tableIdentifier 表标识符
     * @param limit 最大返回数
     * @return 告警列表
     */
    public List<QualityAlert> getRecentAlerts(String tableIdentifier, int limit) {
        return alertBuffer.stream()
                .filter(a -> a.getTableIdentifier().equals(tableIdentifier))
                .limit(limit)
                .toList();
    }

    /**
     * 获取告警统计。
     */
    public java.util.Map<String, Long> getAlertStats() {
        return new java.util.HashMap<>(severityStats);
    }

    /**
     * 清空告警缓冲（用于测试）。
     */
    public void clearAlertBuffer() {
        alertBuffer.clear();
        severityStats.clear();
    }

    // -----------------------------------------------------------------------
    // 私有方法
    // -----------------------------------------------------------------------

    private void sendWebhook(QualityAlert alert) {
        try {
            webhookClient.post()
                    .uri("")
                    .body(alert)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Alert sent to webhook: alertId={}", alert.getAlertId());
        } catch (Exception e) {
            log.warn("Webhook send failed (alert still in buffer): alertId={}, error={}",
                    alert.getAlertId(), e.getMessage());
        }
    }

    private String determineSeverity(QualityRuleResult result) {
        // 根据 ruleType 与 ruleParams 中的 severity 字段确定告警级别
        if (result.getRuleParams() != null) {
            Object severity = result.getRuleParams().get("severity");
            if (severity != null) {
                return severity.toString();
            }
        }
        // 默认：NOT_NULL → ERROR，UNIQUE → WARN，RANGE → WARN，FORMAT → INFO，CUSTOM → WARN
        return switch (result.getRuleType()) {
            case "NOT_NULL" -> "ERROR";
            case "UNIQUE" -> "WARN";
            case "RANGE" -> "WARN";
            case "FORMAT" -> "INFO";
            case "CUSTOM" -> "WARN";
            default -> "WARN";
        };
    }

    private String buildAlertMessage(QualityRuleResult result) {
        return String.format("质量规则违规: rule=%s, type=%s, table=%s, field=%s, value=%s",
                result.getRuleName(), result.getRuleType(),
                result.getTableIdentifier(), result.getFieldName(),
                result.getViolationValue());
    }
}