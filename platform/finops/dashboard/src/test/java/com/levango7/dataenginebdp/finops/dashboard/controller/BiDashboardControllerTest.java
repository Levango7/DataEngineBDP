package com.levango7.dataenginebdp.finops.dashboard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.finops.dashboard.model.DashboardEntity;
import com.levango7.dataenginebdp.finops.dashboard.repository.DashboardRepository;
import com.levango7.dataenginebdp.finops.dashboard.security.TenantContext;
import com.levango7.dataenginebdp.finops.dashboard.service.RealtimeMetricsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BiDashboardController 单元测试（H2 + 真实 Repository）。
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class BiDashboardControllerTest {

    @Autowired
    private DashboardRepository repository;

    private ObjectMapper objectMapper = new ObjectMapper();
    private RealtimeMetricsService realtimeMetricsService = new RealtimeMetricsService();

    @BeforeEach
    void setUpTenant() {
        TenantContext.setTenantId("tenant_a");
        TenantContext.setUserId("tester");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private BiDashboardController controller() {
        return new BiDashboardController(repository, realtimeMetricsService);
    }

    private DashboardEntity seed(String name, String tenantId) {
        return repository.save(DashboardEntity.builder()
                .name(name)
                .description("desc")
                .panelsJson("[{\"title\":\"p1\",\"type\":\"line\"}]")
                .tenantId(tenantId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
    }

    @Test
    void create_persistsAndReturnsView() throws Exception {
        var resp = controller().create(new BiDashboardController.DashboardRequest(
                "销售看板", "desc", objectMapper.readTree("[{\"title\":\"gmv\",\"type\":\"line\"}]")));
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> view = resp.getBody();
        assertThat(view.get("name")).isEqualTo("销售看板");
        assertThat(view.get("panels")).isNotNull();
    }

    @Test
    void list_returnsPagedContract() {
        seed("d1", "tenant_a");
        seed("d2", "tenant_a");
        var resp = controller().list(1, 10);
        Map<String, Object> body = resp.getBody();
        assertThat(body.get("total")).isEqualTo(2);
        assertThat((java.util.List<?>) body.get("list")).hasSize(2);
        assertThat(body.get("page")).isEqualTo(1);
    }

    @Test
    void tenantIsolation() {
        seed("tenant-a-d", "tenant_a");
        seed("tenant-b-d", "tenant_b");
        assertThat(repository.findByTenantIdOrderByCreatedAtDesc("tenant_a")).hasSize(1);
    }

    @Test
    void delete_removesEntity() {
        DashboardEntity e = seed("temp", "tenant_a");
        var resp = controller().delete(e.getId());
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(repository.countByTenantId("tenant_a")).isZero();
    }
}
