package com.levango7.dataenginebdp.encaps.tenant.controller;

import com.levango7.dataenginebdp.encaps.TenantJpaSliceConfig;
import com.levango7.dataenginebdp.encapsshared.model.ProjectEntity;
import com.levango7.dataenginebdp.encapsshared.repository.ProjectRepository;
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
 * ProjectController 单元测试（H2 + 真实 Repository）。
 *
 * <p><b>2026-10-15 上下文改造</b>：原先 {@code @ContextConfiguration(classes = EncapsTenantApplication)}
 * ——切片本不该带全量组件扫描，只因旧版应用的<b>隐式</b>扫描在 @DataJpaTest 切片中
 * 不执行才"碰巧"能跑。应用改为显式 {@code @ComponentScan} 白名单后，切片会执行该
 * 扫描（这是 Spring Boot 的已知行为：隐式扫描不执行、显式注解会），把 encaps-layer 的
 * {@code SecurityConfig}（{@code @Configuration}，TypeExcludeFilter 不排除
 * @Configuration；@DataJpaTest 的 excludeFilters 也不压制显式 @ComponentScan，已实证）
 * 拉进切片，而其依赖的 {@code RateLimitFilter} 只由 common-security 自动配置提供
 * （切片不加载）⇒ 上下文失败。</p>
 *
 * <p>改为自带 {@code @SpringBootConfiguration} 的最小切片配置（{@link TenantJpaSliceConfig}，
 * 刻意置于测试根包 {@code ...encaps} 而非本包——本包在应用 {@code @ComponentScan}
 * 白名单内，配置类若在此会被全 context 测试扫到并造成仓储重复注册，CI 已实证）。
 * 只装配本测试所需的 encapsshared 实体与仓储，JPA 自动配置由 @DataJpaTest 切片自带。</p>
 */
@DataJpaTest
@ContextConfiguration(classes = TenantJpaSliceConfig.class)
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
