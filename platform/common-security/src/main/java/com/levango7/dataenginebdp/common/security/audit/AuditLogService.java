package com.levango7.dataenginebdp.common.security.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;

import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 操作审计日志服务（v2.1 审计合规增强）。
 *
 * <p>实现操作审计全链路覆盖，满足等保三级与金融行业审计要求。</p>
 *
 * <p>核心能力：</p>
 * <ul>
 *   <li>全链路审计：覆盖 HTTP 请求/响应、方法调用、数据操作</li>
 *   <li>敏感字段脱敏：密码、Token、身份证、银行卡等自动脱敏</li>
 *   <li>防篡改：HMAC 签名保证审计日志完整性</li>
 *   <li>异步写入：高并发下不阻塞业务线程</li>
 *   <li>合规保留：等保三级 180 天，金融行业 7 年</li>
 * </ul>
 *
 * <p>等保三级对应条款（GB/T 22239-2019 8.1.4.3）：</p>
 * <ul>
 *   <li>a) 审计覆盖到每个用户，对重要的用户行为和重要安全事件进行审计</li>
 *   <li>b) 审计记录包含日期、时间、用户、事件类型、事件是否成功等</li>
 *   <li>c) 审计记录保护，防止未预期的删除、修改或覆盖</li>
 *   <li>d) 审计进程保护，免受未预期的中断</li>
 * </ul>
 */
public class AuditLogService {

    private static final Logger auditLogger = LoggerFactory.getLogger("AUDIT_LOGGER");
    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "\"(password|passwd|pwd|secret|token|apiKey|api_key|creditCard|credit_card|"
                    + "idCard|id_card|phone|mobile|email|bankAccount|bank_account|cvv|pin)\"\\s*:\\s*\"([^\"]*)\"",
            Pattern.CASE_INSENSITIVE);

    private final AuditConfig config;
    private final ObjectMapper objectMapper;
    private final SecretKeySpec hmacKey;
    private final LinkedBlockingQueue<AuditEvent> asyncQueue;

    /**
     * 构造服务。
     *
     * @param config       审计配置
     * @param objectMapper JSON 序列化器
     */
    public AuditLogService(AuditConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.hmacKey = config.isTamperProof() && config.getHmacSecret() != null
                && !config.getHmacSecret().isEmpty()
                ? new SecretKeySpec(config.getHmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256")
                : null;
        this.asyncQueue = config.isAsyncWrite()
                ? new LinkedBlockingQueue<>(config.getAsyncQueueSize())
                : null;

        if (config.isAsyncWrite()) {
            startAsyncConsumer();
        }
    }

    /**
     * 记录审计事件。
     *
     * @param event 审计事件
     */
    public void audit(AuditEvent event) {
        if (!config.isEnabled()) {
            return;
        }

        if (shouldExclude(event.requestPath())) {
            return;
        }

        AuditEvent processed = processEvent(event);

        if (config.isAsyncWrite()) {
            boolean offered = asyncQueue.offer(processed);
            if (!offered) {
                log.warn("审计日志异步队列已满，事件被丢弃: eventId={}", processed.eventId());
            }
        } else {
            writeEvent(processed);
        }
    }

    /**
     * 异步记录审计事件。
     *
     * @param event 审计事件
     */
    @Async("auditTaskExecutor")
    public void auditAsync(AuditEvent event) {
        audit(event);
    }

    /**
     * 构造审计事件构建器。
     *
     * @return 构建器
     */
    public static AuditEventBuilder builder() {
        return new AuditEventBuilder();
    }

    /**
     * 处理审计事件（脱敏 + 签名）。
     *
     * @param event 原始事件
     * @return 处理后事件
     */
    private AuditEvent processEvent(AuditEvent event) {
        String sanitizedParams = sanitizeSensitiveData(event.requestParams());
        String sanitizedMetadata = sanitizeSensitiveData(event.metadata());

        String truncatedParams = truncate(sanitizedParams, config.getRequestParamsMaxLength());

        return new AuditEvent(
                event.eventId() != null ? event.eventId() : UUID.randomUUID().toString(),
                event.timestamp() != null ? event.timestamp() : Instant.now(),
                event.traceId(),
                event.userId(),
                event.tenantId(),
                event.actionType(),
                event.action(),
                event.resource(),
                event.resourceId(),
                event.sourceIp(),
                event.userAgent(),
                event.requestMethod(),
                event.requestPath(),
                truncatedParams,
                event.responseStatus(),
                event.responseTimeMs(),
                event.result(),
                event.errorMessage(),
                event.sessionId(),
                event.level() != null ? event.level() : AuditEvent.Level.INFO,
                event.category() != null ? event.category() : AuditEvent.Category.DATA_OPERATION,
                sanitizedMetadata
        );
    }

    /**
     * 写入审计事件到日志。
     *
     * @param event 审计事件
     */
    private void writeEvent(AuditEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            String signedJson = signAuditLog(json);
            auditLogger.info(signedJson);
        } catch (JsonProcessingException e) {
            log.error("审计事件序列化失败: eventId={}", event.eventId(), e);
        }
    }

    /**
     * 对审计日志进行 HMAC 签名（防篡改）。
     *
     * @param json 审计日志 JSON
     * @return 带签名的审计日志
     */
    private String signAuditLog(String json) {
        if (hmacKey == null) {
            return json;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            byte[] signatureBytes = mac.doFinal(json.getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getEncoder().encodeToString(signatureBytes);
            return "{\"payload\":" + json + ",\"signature\":\"" + signature + "\"}";
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("审计日志签名失败", e);
            return json;
        }
    }

    /**
     * 敏感字段脱敏。
     *
     * @param data 原始数据
     * @return 脱敏后数据
     */
    private String sanitizeSensitiveData(String data) {
        if (data == null || data.isEmpty()) {
            return data;
        }
        Matcher matcher = SENSITIVE_PATTERN.matcher(data);
        return matcher.replaceAll(m -> "\"" + m.group(1) + "\":\"****\"");
    }

    /**
     * 截断字符串到最大长度。
     *
     * @param value    原始值
     * @param maxLength 最大长度
     * @return 截断后值
     */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...[truncated]";
    }

    /**
     * 判断路径是否需要排除。
     *
     * @param path 请求路径
     * @return 是否排除
     */
    private boolean shouldExclude(String path) {
        if (path == null) {
            return false;
        }
        if (config.getExcludePaths() != null) {
            for (String exclude : config.getExcludePaths()) {
                if (path.startsWith(exclude)) {
                    return true;
                }
            }
        }
        if (config.getIncludePaths() != null && !config.getIncludePaths().isEmpty()) {
            for (String include : config.getIncludePaths()) {
                if (path.startsWith(include)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 启动异步消费者线程。
     */
    private void startAsyncConsumer() {
        Thread consumer = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    AuditEvent event = asyncQueue.poll(1, TimeUnit.SECONDS);
                    if (event != null) {
                        writeEvent(event);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("审计日志异步消费失败", e);
                }
            }
        }, "audit-log-consumer");
        consumer.setDaemon(true);
        consumer.start();
        log.info("审计日志异步消费者已启动，队列大小={}", config.getAsyncQueueSize());
    }

    /**
     * 审计事件构建器。
     */
    public static class AuditEventBuilder {
        private String eventId;
        private Instant timestamp;
        private String traceId;
        private String userId;
        private String tenantId;
        private AuditEvent.ActionType actionType;
        private String action;
        private String resource;
        private String resourceId;
        private String sourceIp;
        private String userAgent;
        private String requestMethod;
        private String requestPath;
        private String requestParams;
        private int responseStatus;
        private long responseTimeMs;
        private AuditEvent.Result result;
        private String errorMessage;
        private String sessionId;
        private AuditEvent.Level level;
        private AuditEvent.Category category;
        private String metadata;

        public AuditEventBuilder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        public AuditEventBuilder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public AuditEventBuilder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public AuditEventBuilder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public AuditEventBuilder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public AuditEventBuilder actionType(AuditEvent.ActionType actionType) {
            this.actionType = actionType;
            return this;
        }

        public AuditEventBuilder action(String action) {
            this.action = action;
            return this;
        }

        public AuditEventBuilder resource(String resource) {
            this.resource = resource;
            return this;
        }

        public AuditEventBuilder resourceId(String resourceId) {
            this.resourceId = resourceId;
            return this;
        }

        public AuditEventBuilder sourceIp(String sourceIp) {
            this.sourceIp = sourceIp;
            return this;
        }

        public AuditEventBuilder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public AuditEventBuilder requestMethod(String requestMethod) {
            this.requestMethod = requestMethod;
            return this;
        }

        public AuditEventBuilder requestPath(String requestPath) {
            this.requestPath = requestPath;
            return this;
        }

        public AuditEventBuilder requestParams(String requestParams) {
            this.requestParams = requestParams;
            return this;
        }

        public AuditEventBuilder responseStatus(int responseStatus) {
            this.responseStatus = responseStatus;
            return this;
        }

        public AuditEventBuilder responseTimeMs(long responseTimeMs) {
            this.responseTimeMs = responseTimeMs;
            return this;
        }

        public AuditEventBuilder result(AuditEvent.Result result) {
            this.result = result;
            return this;
        }

        public AuditEventBuilder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public AuditEventBuilder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public AuditEventBuilder level(AuditEvent.Level level) {
            this.level = level;
            return this;
        }

        public AuditEventBuilder category(AuditEvent.Category category) {
            this.category = category;
            return this;
        }

        public AuditEventBuilder metadata(String metadata) {
            this.metadata = metadata;
            return this;
        }

        public AuditEvent build() {
            return new AuditEvent(
                    eventId, timestamp, traceId, userId, tenantId,
                    actionType, action, resource, resourceId,
                    sourceIp, userAgent, requestMethod, requestPath,
                    requestParams, responseStatus, responseTimeMs,
                    result, errorMessage, sessionId, level, category, metadata
            );
        }
    }
}