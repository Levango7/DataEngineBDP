package com.shuqing.bigdata.encaps.security.facade.audit;

import com.shuqing.bigdata.encaps.security.facade.config.SecurityFacadeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AuditFacade} 单元测试。
 *
 * <p>覆盖事件记录、查询过滤、环形缓冲、禁用异常等。</p>
 */
class AuditFacadeTest {

    private AuditFacade auditFacade;
    private SecurityFacadeConfig config;

    @BeforeEach
    void setUp() {
        config = new SecurityFacadeConfig();
        auditFacade = new AuditFacade(config);
    }

    // ===== 记录与查询 =====

    @Test
    @DisplayName("record — 记录事件后可查询")
    void record_shouldBeQueryable() {
        AuditEvent event = AuditEvent.builder()
                .action("LOGIN")
                .tenantId("t1")
                .userId("u1")
                .build();

        auditFacade.record(event);

        assertThat(auditFacade.size()).isEqualTo(1);
        assertThat(auditFacade.list()).containsExactly(event);
    }

    @Test
    @DisplayName("record(action) — RecordingBuilder 自动记录")
    void recordAction_shouldAutoRecord() {
        auditFacade.record("LOGIN")
                .tenantId("t1")
                .userId("u1")
                .build();

        assertThat(auditFacade.size()).isEqualTo(1);
        AuditEvent recorded = auditFacade.list().get(0);
        assertThat(recorded.getAction()).isEqualTo("LOGIN");
        assertThat(recorded.getTenantId()).isEqualTo("t1");
        assertThat(recorded.getUserId()).isEqualTo("u1");
    }

    @Test
    @DisplayName("listByLevel — 按级别过滤")
    void listByLevel_shouldFilter() {
        auditFacade.record(AuditEvent.builder().action("a1").level(AuditLevel.INFO).build());
        auditFacade.record(AuditEvent.builder().action("a2").level(AuditLevel.WARN).build());
        auditFacade.record(AuditEvent.builder().action("a3").level(AuditLevel.INFO).build());

        assertThat(auditFacade.listByLevel(AuditLevel.INFO)).hasSize(2);
        assertThat(auditFacade.listByLevel(AuditLevel.WARN)).hasSize(1);
        assertThat(auditFacade.listByLevel(AuditLevel.CRITICAL)).isEmpty();
    }

    @Test
    @DisplayName("listByTenant — 按租户过滤")
    void listByTenant_shouldFilter() {
        auditFacade.record(AuditEvent.builder().action("a1").tenantId("t1").build());
        auditFacade.record(AuditEvent.builder().action("a2").tenantId("t2").build());

        assertThat(auditFacade.listByTenant("t1")).hasSize(1);
        assertThat(auditFacade.listByTenant("t2")).hasSize(1);
        assertThat(auditFacade.listByTenant("t3")).isEmpty();
    }

    @Test
    @DisplayName("listByTimeRange — 按时间范围过滤")
    void listByTimeRange_shouldFilter() {
        Instant t1 = Instant.now().minusSeconds(100);
        Instant t2 = Instant.now().minusSeconds(50);
        Instant t3 = Instant.now();

        auditFacade.record(AuditEvent.builder().action("a1").timestamp(t1).build());
        auditFacade.record(AuditEvent.builder().action("a2").timestamp(t2).build());
        auditFacade.record(AuditEvent.builder().action("a3").timestamp(t3).build());

        assertThat(auditFacade.listByTimeRange(t1, t3)).hasSize(2);
        assertThat(auditFacade.listByTimeRange(t2, t3)).hasSize(1);
    }

    // ===== 环形缓冲 =====

    @Test
    @DisplayName("环形缓冲 — 超过上限丢弃最旧事件")
    void ringBuffer_shouldDropOldest() {
        config.getAudit().setMaxEventsRetained(3);
        AuditFacade smallBuffer = new AuditFacade(config);

        for (int i = 0; i < 5; i++) {
            smallBuffer.record(AuditEvent.builder().action("a" + i).build());
        }

        assertThat(smallBuffer.size()).isEqualTo(3);
        // 应保留 a2, a3, a4
        assertThat(smallBuffer.list().get(0).getAction()).isEqualTo("a2");
        assertThat(smallBuffer.list().get(2).getAction()).isEqualTo("a4");
    }

    // ===== 禁用 =====

    @Test
    @DisplayName("禁用后记录抛 IllegalStateException")
    void disabled_shouldThrow() {
        config.setEnabled(false);
        AuditEvent event = AuditEvent.builder().action("test").build();

        assertThatThrownBy(() -> auditFacade.record(event))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("仅审计禁用 — 抛 IllegalStateException")
    void auditDisabled_shouldThrow() {
        config.getAudit().setEnabled(false);
        AuditEvent event = AuditEvent.builder().action("test").build();

        assertThatThrownBy(() -> auditFacade.record(event))
                .isInstanceOf(IllegalStateException.class);
    }

    // ===== clear =====

    @Test
    @DisplayName("clear — 清空缓冲")
    void clear_shouldEmptyBuffer() {
        auditFacade.record(AuditEvent.builder().action("a1").build());
        auditFacade.record(AuditEvent.builder().action("a2").build());

        auditFacade.clear();

        assertThat(auditFacade.size()).isZero();
        assertThat(auditFacade.list()).isEmpty();
    }

    // ===== AuditEvent 不可变性 =====

    @Test
    @DisplayName("AuditEvent — details 不可变")
    void auditEvent_detailsImmutable() {
        AuditEvent event = AuditEvent.builder()
                .action("test")
                .detail("key", "value")
                .build();

        Map<String, String> details = event.getDetails();
        assertThatThrownBy(() -> details.put("newKey", "newValue"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("AuditEvent — toMap 包含所有字段")
    void auditEvent_toMap_shouldContainAllFields() {
        AuditEvent event = AuditEvent.builder()
                .action("LOGIN")
                .level(AuditLevel.INFO)
                .tenantId("t1")
                .userId("u1")
                .resource("/api/v1/test")
                .result("SUCCESS")
                .detail("ip", "192.168.1.1")
                .build();

        Map<String, Object> map = event.toMap();
        assertThat(map).containsKeys("timestamp", "level", "action", "tenantId", "userId", "resource", "result", "details");
        assertThat(map.get("action")).isEqualTo("LOGIN");
        assertThat(map.get("level")).isEqualTo("INFO");
    }
}