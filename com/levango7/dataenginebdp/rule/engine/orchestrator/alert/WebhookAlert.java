package com.shuqing.bigdata.rule.engine.orchestrator.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Webhook 告警通道。
 *
 * <p>将告警事件序列化为 JSON POST 到配置的 Webhook URL，适用于钉钉/飞书/企业微信机器人
 * 或自建告警接收服务。使用 JDK 11+ 内置 HttpClient，无需额外依赖。</p>
 *
 * <p>设计说明：
 * <ul>
 *   <li>connect/read 超时可配，避免下游不可达时阻塞调度线程；</li>
 *   <li>非 2xx 响应视为失败，返回 false 由上层决定是否记录；</li>
 *   <li>HttpClient 单例复用，避免每次告警新建连接池。</li>
 * </ul>
 * </p>
 */
@Component
public class WebhookAlert implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(WebhookAlert.class);

    private final boolean enabled;
    private final String url;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WebhookAlert(@Value("${app.orchestrator.alert.webhook.enabled:false}") boolean enabled,
                        @Value("${app.orchestrator.alert.webhook.url:}") String url,
                        @Value("${app.orchestrator.alert.webhook.connect-timeout-ms:3000}") int connectTimeoutMs,
                        @Value("${app.orchestrator.alert.webhook.read-timeout-ms:5000}") int readTimeoutMs) {
        this.enabled = enabled;
        this.url = url;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public boolean send(AlertEvent event) {
        if (!enabled || url == null || url.isBlank()) {
            log.debug("webhook alert disabled or url empty, skip event={}", event.getId());
            return false;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", event.getId());
            payload.put("type", event.getType());
            payload.put("level", event.getLevel());
            payload.put("dagId", event.getDagId());
            payload.put("nodeId", event.getNodeId());
            payload.put("title", event.getTitle());
            payload.put("message", event.getMessage());
            payload.put("triggeredAt", event.getTriggeredAt() == null ? null : event.getTriggeredAt().toString());
            payload.put("extras", event.getExtras());

            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                log.info("[WEBHOOK-ALERT] sent ok status={} url={} event={}", code, url, event.getId());
                return true;
            }
            log.warn("[WEBHOOK-ALERT] non-2xx status={} body={} url={} event={}",
                    code, response.body(), url, event.getId());
            return false;
        } catch (Exception e) {
            log.warn("[WEBHOOK-ALERT] send failed url={} event={} error={}", url, event.getId(), e.getMessage());
            return false;
        }
    }

    @Override
    public String name() {
        return "WEBHOOK";
    }
}