package com.levango7.dataenginebdp.common.security.audit.jpa;

import com.levango7.dataenginebdp.common.security.audit.AuditEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ContextConfiguration;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计 JPA Sink + 组合查询测试（C2，H2 内存库）。
 *
 * <p>common-security 是 Starter 店无 @SpringBootConfiguration 主类，
 * 显式 ContextConfiguration 指向内嵌测试配置（JPA Repository 扫描 + Service）。</p>
 */
@DataJpaTest
@ContextConfiguration(classes = AuditJpaTestConfig.class)
@Import(AuditQueryService.class)
class AuditJpaPersistenceTest {

    @Autowired
    private AuditLogJpaRepository repository;

    @Autowired
    private AuditQueryService queryService;

    /** 构造并落库一条审计事件（经 JpaAuditSink 的字段映射逻辑）。 */
    private void persist(String userId, String tenantId, String action,
                         String resource, Instant ts) {
        AuditQueryService.JpaAuditSink sink = new AuditQueryService.JpaAuditSink(repository);
        // 容器外 new 的 Sink 不会触发 @PostConstruct selfCheck，手动置绪
        sink.selfCheck();
        sink.persist(new AuditEvent(
                UUID.randomUUID().toString(), ts, "trace-x", userId, tenantId,
                AuditEvent.ActionType.CREATE, action, resource, "res-1",
                "10.0.0.1", "ua-test", "POST", "/api/v1/test",
                "{}", 200, 15L, AuditEvent.Result.SUCCESS, null,
                null, AuditEvent.Level.INFO, AuditEvent.Category.DATA_OPERATION, "{}"));
    }

    @Test
    @DisplayName("Sink 持久化：AuditEvent 全字段映射落库")
    void sinkPersistsAllFields() {
        persist("alice", "tenant_a", "CREATE_DATASOURCE", "datasource", Instant.now());

        assertThat(repository.count()).isEqualTo(1);
        AuditLogEntity saved = repository.findAll().get(0);
        assertThat(saved.getUserId()).isEqualTo("alice");
        assertThat(saved.getTenantId()).isEqualTo("tenant_a");
        assertThat(saved.getAction()).isEqualTo("CREATE_DATASOURCE");
        assertThat(saved.getResource()).isEqualTo("datasource");
        assertThat(saved.getActionType()).isEqualTo("CREATE");
        assertThat(saved.getResult()).isEqualTo("SUCCESS");
        assertThat(saved.getLevel()).isEqualTo("INFO");
        assertThat(saved.getResponseStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("组合查询：按用户+动作过滤命中")
    void queryByUserAndAction() {
        persist("alice", "tenant_a", "LOGIN", "auth", Instant.now());
        persist("alice", "tenant_a", "CREATE_DATASOURCE", "datasource", Instant.now());
        persist("bob", "tenant_a", "LOGIN", "auth", Instant.now());

        Page<AuditLogEntity> page = queryService.query(
                "alice", null, "LOGIN", null, null, null, null,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "timestamp")));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getUserId()).isEqualTo("alice");
        assertThat(page.getContent().get(0).getAction()).isEqualTo("LOGIN");
    }

    @Test
    @DisplayName("租户隔离：查询按租户过滤")
    void queryTenantIsolation() {
        persist("alice", "tenant_a", "LOGIN", "auth", Instant.now());
        persist("alice", "tenant_b", "LOGIN", "auth", Instant.now());

        Page<AuditLogEntity> page = queryService.query(
                null, "tenant_b", null, null, null, null, null,
                PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getTenantId()).isEqualTo("tenant_b");
    }

    @Test
    @DisplayName("时间范围查询：from/to 边界过滤")
    void queryTimeRange() {
        Instant t1 = Instant.parse("2026-09-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-09-02T00:00:00Z");
        Instant t3 = Instant.parse("2026-09-03T00:00:00Z");
        persist("u1", "tenant_a", "LOGIN", "auth", t1);
        persist("u1", "tenant_a", "LOGIN", "auth", t2);
        persist("u1", "tenant_a", "LOGIN", "auth", t3);

        // [t1+1s, t3-1s) 只命中 t2
        Page<AuditLogEntity> page = queryService.query(
                null, "tenant_a", null, null, null,
                Instant.parse("2026-09-01T00:00:01Z"),
                Instant.parse("2026-09-02T23:59:59Z"),
                PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getTimestamp()).isEqualTo(t2);
    }

    @Test
    @DisplayName("eventId 唯一性：同 eventId 幂等对账查询")
    void eventIdLookup() {
        persist("alice", "tenant_a", "LOGIN", "auth", Instant.now());
        AuditLogEntity saved = repository.findAll().get(0);

        assertThat(repository.findByEventId(saved.getEventId())).isNotNull();
    }
}
