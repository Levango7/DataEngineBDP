package com.levango7.dataenginebdp.common.security.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计日志服务单元测试（v2.1 审计合规增强）。
 */
class AuditLogServiceTest {

    private AuditConfig config;
    private ObjectMapper objectMapper;
    private AuditLogService auditLogService;
    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void setUp() {
        config = new AuditConfig();
        config.setEnabled(true);
        config.setAsyncWrite(false);
        config.setLogRequestParams(true);
        config.setRequestParamsMaxLength(2000);
        config.setTamperProof(false);

        objectMapper = new ObjectMapper();
        auditLogService = new AuditLogService(config, objectMapper);

        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT_LOGGER");
        listAppender = new ListAppender<>();
        listAppender.start();
        auditLogger.addAppender(listAppender);
    }

    @Test
    void shouldNotAuditWhenDisabled() {
        config.setEnabled(false);
        AuditEvent event = buildTestEvent();
        auditLogService.audit(event);
        assertTrue(listAppender.list.isEmpty(), "禁用后不应记录审计日志");
    }

    @Test
    void shouldAuditEventWithAllFields() {
        AuditEvent event = buildTestEvent();
        auditLogService.audit(event);

        assertEquals(1, listAppender.list.size(), "应记录一条审计日志");
        String logMessage = listAppender.list.get(0).getFormattedMessage();
        assertTrue(logMessage.contains("userId"), "审计日志应包含 userId");
        assertTrue(logMessage.contains("tenantId"), "审计日志应包含 tenantId");
        assertTrue(logMessage.contains("actionType"), "审计日志应包含 actionType");
        assertTrue(logMessage.contains("result"), "审计日志应包含 result");
    }

    @Test
    void shouldSanitizeSensitiveFields() {
        AuditEvent event = AuditLogService.builder()
                .eventId("test-1")
                .timestamp(Instant.now())
                .userId("user1")
                .tenantId("tenant1")
                .actionType(AuditEvent.ActionType.LOGIN)
                .action("LOGIN")
                .requestParams("{\"username\":\"admin\",\"password\":\"secret123\"}")
                .result(AuditEvent.Result.SUCCESS)
                .category(AuditEvent.Category.AUTHENTICATION)
                .build();

        auditLogService.audit(event);

        String logMessage = listAppender.list.get(0).getFormattedMessage();
        assertTrue(logMessage.contains("****"), "密码应被脱敏为 ****");
        assertTrue(!logMessage.contains("secret123"), "原始密码不应出现在日志中");
    }

    @Test
    void shouldExcludeHealthCheckPaths() {
        config.setExcludePaths(java.util.List.of("/actuator/health"));
        AuditEvent event = AuditLogService.builder()
                .eventId("test-2")
                .timestamp(Instant.now())
                .actionType(AuditEvent.ActionType.QUERY)
                .requestPath("/actuator/health")
                .result(AuditEvent.Result.SUCCESS)
                .build();

        auditLogService.audit(event);
        assertTrue(listAppender.list.isEmpty(), "健康检查路径应被排除");
    }

    @Test
    void shouldTruncateLongParams() {
        config.setRequestParamsMaxLength(10);
        String longParams = "a".repeat(100);
        AuditEvent event = AuditLogService.builder()
                .eventId("test-3")
                .timestamp(Instant.now())
                .actionType(AuditEvent.ActionType.QUERY)
                .requestParams(longParams)
                .result(AuditEvent.Result.SUCCESS)
                .build();

        auditLogService.audit(event);

        String logMessage = listAppender.list.get(0).getFormattedMessage();
        assertTrue(logMessage.contains("truncated"), "超长参数应被截断");
    }

    @Test
    void shouldGenerateEventIdWhenMissing() {
        AuditEvent event = AuditLogService.builder()
                .timestamp(Instant.now())
                .actionType(AuditEvent.ActionType.QUERY)
                .result(AuditEvent.Result.SUCCESS)
                .build();

        auditLogService.audit(event);

        String logMessage = listAppender.list.get(0).getFormattedMessage();
        assertTrue(logMessage.contains("eventId"), "应自动生成 eventId");
    }

    @Test
    void shouldSignAuditLogWhenTamperProofEnabled() {
        config.setTamperProof(true);
        config.setHmacSecret("test-hmac-secret-key-at-least-32-bytes-long");
        auditLogService = new AuditLogService(config, objectMapper);

        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT_LOGGER");
        auditLogger.detachAndStopAllAppenders();
        listAppender = new ListAppender<>();
        listAppender.start();
        auditLogger.addAppender(listAppender);

        AuditEvent event = buildTestEvent();
        auditLogService.audit(event);

        String logMessage = listAppender.list.get(0).getFormattedMessage();
        assertTrue(logMessage.contains("signature"), "防篡改模式下应包含签名");
        assertTrue(logMessage.contains("payload"), "防篡改模式下应包含 payload");
    }

    /**
     * 构造测试审计事件。
     *
     * @return 审计事件
     */
    private AuditEvent buildTestEvent() {
        return AuditLogService.builder()
                .eventId("test-event-1")
                .timestamp(Instant.now())
                .traceId("trace-123")
                .userId("user1")
                .tenantId("tenant1")
                .actionType(AuditEvent.ActionType.QUERY)
                .action("GET /api/v1/clusters")
                .resource("/api/v1/clusters")
                .sourceIp("192.168.1.100")
                .userAgent("Mozilla/5.0")
                .requestMethod("GET")
                .requestPath("/api/v1/clusters")
                .requestParams("page=1&size=10")
                .responseStatus(200)
                .responseTimeMs(50)
                .result(AuditEvent.Result.SUCCESS)
                .sessionId("session-1")
                .level(AuditEvent.Level.INFO)
                .category(AuditEvent.Category.DATA_OPERATION)
                .build();
    }
}