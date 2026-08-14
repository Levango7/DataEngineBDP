package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.model.ProjectEntity;
import com.levango7.dataenginebdp.encaps.repository.ProjectRepository;
import com.levango7.dataenginebdp.encaps.security.TenantContext;
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
 * ProjectController 单元测试（H2 + 真实 Repository）。
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class ProjectControllerTest {

    @Autowired
    private ProjectRepository repository;

    @BeforeEach
    void setUpTenant() {
        TenantContext.setTenantId("tenant_a");
        TenantContext.setUserId("tester");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private ProjectController controller() {
        return new ProjectController(repository);
    }

    private ProjectEntity seed(String name, String tenantId) {
        return repository.save(ProjectEntity.builder()
                .name(name)
                .domain("finance")
                .description("test")
                .status("active")
                .datasets(0)
                .jobs(0)
                .tenantId(tenantId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
    }

    @Test
    void create_persistsAndReturnsView() {
        var resp = controller().create(new ProjectController.ProjectRequest("风控项目", "finance", "desc"));
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> view = resp.getBody();
        assertThat(view.get("name")).isEqualTo("风控项目");
        assertThat(view.get("domain")).isEqualTo("finance");
        assertThat(view.get("status")).isEqualTo("active");
        assertThat(view.get("datasets")).isEqualTo(0);
    }

    @Test
    void list_returnsPagedContract() {
        seed("p1", "tenant_a");
        seed("p2", "tenant_a");

        var resp = controller().list(null, 1, 10);
        Map<String, Object> body = resp.getBody();
        assertThat(body.get("total")).isEqualTo(2);
        assertThat((java.util.List<?>) body.get("list")).hasSize(2);
        assertThat(body.get("page")).isEqualTo(1);
    }

    @Test
    void tenantIsolation() {
        seed("tenant-a-p", "tenant_a");
        seed("tenant-b-p", "tenant_b");
        assertThat(repository.findByTenantIdOrderByCreatedAtDesc("tenant_a")).hasSize(1);
    }

    @Test
    void delete_removesEntity() {
        ProjectEntity e = seed("temp", "tenant_a");
        var resp = controller().delete(e.getId());
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(repository.countByTenantId("tenant_a")).isZero();
    }

    @Test
    void update_modifiesEntity() {
        ProjectEntity e = seed("old", "tenant_a");
        var resp = controller().update(e.getId(),
                new ProjectController.ProjectRequest("new", "risk", "updated"));
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(repository.findById(e.getId()).orElseThrow().getName()).isEqualTo("new");
    }
}
