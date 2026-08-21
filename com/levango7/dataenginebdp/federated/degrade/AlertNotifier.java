package com.shuqing.bigdata.federated.degrade;

import com.shuqing.bigdata.federated.model.DegradationAlert;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 告警通知器。
 *
 * <p>收集降级告警事件，提供：
 * <ul>
 *   <li>内存事件存储（最近 N 条），供 REST API 查询</li>
 *   <li>Webhook 推送（可选，配置 {@code federated.degrade.alert-webhook-url}）</li>
 *   <li>日志输出（始终启用）</li>
 * </ul>
 */
@Slf4j
@Component
public class AlertNotifier {

    private final com.shuqing.bigdata.federated.config.FederatedQueryProperties props;
    private final List<DegradationAlert> events = new CopyOnWriteArrayList<>();
    private static final int MAX_EVENTS = 1000;

    public AlertNotifier(com.shuqing.bigdata.federated.config.FederatedQueryProperties props) {
        this.props = props;
    }

    /**
     * 触发告警。
     */
    public DegradationAlert alert(String severity, String type, String cluster, String message, String degradedTo) {
        DegradationAlert alert = DegradationAlert.builder()
                .alertId(UUID.randomUUID().toString())
                .severity(severity)
                .type(type)
                .cluster(cluster)
                .message(message)
                .timestamp(Instant.now())
                .recovered(false)
                .degradedTo(degradedTo)
                .build();
        record(alert);
        return alert;
    }

    /**
     * 触发恢复告警。
     */
    public DegradationAlert recover(String cluster) {
        DegradationAlert alert = DegradationAlert.builder()
                .alertId(UUID.randomUUID().toString())
                .severity("INFO")
                .type("DEGRADE_RECOVERED")
                .cluster(cluster)
                .message("Cluster " + cluster + " recovered, federated query resumed")
                .timestamp(Instant.now())
                .recovered(true)
                .build();
        record(alert);
        return alert;
    }

    /**
     * 列出最近告警事件。
     */
    public List<DegradationAlert> listRecent(int limit) {
        int size = events.size();
        int from = Math.max(0, size - limit);
        return new ArrayList<>(events.subList(from, size));
    }

    /**
     * 列出全部告警事件（受 MAX_EVENTS 上限）。
     */
    public List<DegradationAlert> listAll() {
        return new ArrayList<>(events);
    }

    private void record(DegradationAlert alert) {
        // 日志输出（始终启用）
        switch (alert.getSeverity()) {
            case "CRITICAL" -> log.error("[ALERT] {} cluster={} msg={} degradedTo={}",
                    alert.getType(), alert.getCluster(), alert.getMessage(), alert.getDegradedTo());
            case "ERROR" -> log.error("[ALERT] {} cluster={} msg={}",
                    alert.getType(), alert.getCluster(), alert.getMessage());
            case "WARN" -> log.warn("[ALERT] {} cluster={} msg={}",
                    alert.getType(), alert.getCluster(), alert.getMessage());
            default -> log.info("[ALERT] {} cluster={} msg={}",
                    alert.getType(), alert.getCluster(), alert.getMessage());
        }

        // 内存存储
        events.add(alert);
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }

        // Webhook 推送（可选）
        if (props.getDegrade().isAlertEnabled() && props.getDegrade().getAlertWebhookUrl() != null) {
            pushWebhook(alert);
        }
    }

    private void pushWebhook(DegradationAlert alert) {
        // 简化实现：实际生产应使用 WebClient 异步推送
        log.info("Webhook push (stub): {} -> {}", alert.getAlertId(), props.getDegrade().getAlertWebhookUrl());
    }
}