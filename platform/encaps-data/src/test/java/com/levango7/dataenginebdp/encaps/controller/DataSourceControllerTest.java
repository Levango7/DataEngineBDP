package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.model.DataSourceEntity;
import com.levango7.dataenginebdp.encaps.repository.DataSourceRepository;
import com.levango7.dataenginebdp.common.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DataSourceController 单元测试（H2 + 真实 Repository）。
 */
@DataJpaTest
@ContextConfiguration(classes = com.levango7.dataenginebdp.encaps.EncapsDataApplication.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class DataSourceControllerTest {

    @Autowired
    private DataSourceRepository repository;

    @BeforeEach
    void setUpTenant() {
        TenantContext.setTenantId("tenant_a");
        TenantContext.setUserId("tester");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private DataSourceController controller() {
        return new DataSourceController(repository);
    }

    private DataSourceEntity seed(String name, String tenantId) {
        DataSourceEntity e = DataSourceEntity.builder()
                .name(name)
                .type("mysql")
                .host("127.0.0.1")
                .port(3306)
                .database("test")
                .username("root")
                .password("secret-pwd")
                .status("disconnected")
                .tenantId(tenantId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return repository.save(e);
    }

    @Test
    void create_persistsAndHidesPassword() {
        var resp = controller().create(new DataSourceController.DataSourceRequest(
                "订单库", "mysql", "db.internal", 3306, "orders", "root", "p@ss"));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> view = resp.getBody();
        assertThat(view.get("name")).isEqualTo("订单库");
        assertThat(view).doesNotContainKey("password"); // 密码不返回
        assertThat(view.get("status")).isEqualTo("disconnected");
    }

    @Test
    void tenantIsolation_otherTenantCannotSee() {
        seed("tenant-a-ds", "tenant_a");
        seed("tenant-b-ds", "tenant_b");

        // 模拟 tenant_a 上下文（controller 直调时手动断言 repo 隔离）
        var listA = repository.findByTenantIdOrderByCreatedAtDesc("tenant_a");
        assertThat(listA).hasSize(1);
        assertThat(listA.get(0).getName()).isEqualTo("tenant-a-ds");
    }

    @Test
    void delete_removesEntity() {
        DataSourceEntity e = seed("temp-ds", "tenant_a");
        var resp = controller().delete(e.getId());
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(repository.countByTenantId("tenant_a")).isZero();
    }

    @Test
    void update_keepsPasswordWhenBlank() {
        DataSourceEntity e = seed("keep-pwd", "tenant_a");
        var resp = controller().update(e.getId(), new DataSourceController.DataSourceRequest(
                "keep-pwd-renamed", "mysql", "new-host", 3306, null, "root", null));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        DataSourceEntity updated = repository.findById(e.getId()).orElseThrow();
        assertThat(updated.getPassword()).isEqualTo("secret-pwd"); // 密码留空不覆盖
        assertThat(updated.getHost()).isEqualTo("new-host");
    }

    @Test
    void create_duplicateNameInSameTenantReturns409() {
        seed("dup-ds", "tenant_a");

        var resp = controller().create(new DataSourceController.DataSourceRequest(
                "dup-ds", "mysql", "db.internal", 3306, "orders", "root", "p@ss"));

        // A3 幂等性：同租户同名 → 409 + messageKey（前端 i18n 翻译）
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        Map<String, Object> body = resp.getBody();
        assertThat(body.get("code")).isEqualTo(40901);
        assertThat(body.get("messageKey")).isEqualTo("error.resource.conflict");
        assertThat(body.get("conflictField")).isEqualTo("name");
        // 未产生第二条
        assertThat(repository.countByTenantId("tenant_a")).isEqualTo(1);
    }

    @Test
    void create_sameNameDifferentTenantIsAllowed() {
        seed("shared-name", "tenant_a");

        // A3 幂等边界：唯一性是"租户内"，跨租户同名合法
        TenantContext.setTenantId("tenant_b");
        var resp = controller().create(new DataSourceController.DataSourceRequest(
                "shared-name", "mysql", "db.internal", 3306, "orders", "root", "p@ss"));

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        assertThat(repository.countByTenantId("tenant_b")).isEqualTo(1);
    }
}
