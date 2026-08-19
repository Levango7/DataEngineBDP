package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.model.SyncTaskEntity;
import com.levango7.dataenginebdp.encaps.repository.ApiDefinitionRepository;
import com.levango7.dataenginebdp.encaps.repository.AssetRepository;
import com.levango7.dataenginebdp.encaps.repository.DataSourceRepository;
import com.levango7.dataenginebdp.encaps.repository.ProjectRepository;
import com.levango7.dataenginebdp.encaps.repository.SyncTaskRepository;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.service.IntegrateConnectorService;
import com.levango7.dataenginebdp.encaps.service.SeaTunnelClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integrate/Admin 端点单测（H2 + 真实 Repository）。
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class IntegrateAdminControllerTest {

    @Autowired private SyncTaskRepository syncTaskRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private ApiDefinitionRepository apiRepository;
    @Autowired private DataSourceRepository dataSourceRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private com.levango7.dataenginebdp.encaps.workspace.WorkspaceRepository workspaceRepository;
    @Autowired private com.levango7.dataenginebdp.encaps.quota.QuotaRepository quotaRepository;

    @BeforeEach
    void setUpTenant() {
        TenantContext.setTenantId("tenant_a");
        TenantContext.setUserId("tester");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void integrate_syncTaskCrud() {
        var c = new IntegrateController(syncTaskRepository,
                Mockito.mock(IntegrateConnectorService.class),
                Mockito.mock(SeaTunnelClient.class));
        var created = c.createTask(new IntegrateController.SyncTaskRequest(
                "订单同步", "mysql", "iceberg", "ods.orders", "ods_orders", "0 */5 * * * ?"));
        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> view = created.getBody();
        assertThat(view.get("sourceType")).isEqualTo("mysql");
        assertThat(view.get("status")).isEqualTo("pending");

        var list = c.listTasks(1, 20);
        assertThat((Number) list.getBody().get("total")).isEqualTo(1);

        c.deleteTask(Long.valueOf((String) view.get("id")));
        assertThat(syncTaskRepository.countByTenantId("tenant_a")).isZero();
    }

    @Test
    void admin_kpiAggregatesRealCounts() {
        // 预置数据
        assetRepository.save(com.levango7.dataenginebdp.encaps.model.AssetEntity.builder()
                .name("a1").type("table").owner("t1").status("published")
                .qualityScore(80).securityLevel("L2").fullJson("{}").tenantId("tenant_a")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());

        var c = new AdminController(workspaceRepository, quotaRepository, assetRepository, apiRepository,
                dataSourceRepository, projectRepository, syncTaskRepository);
        var resp = c.kpi();
        Map<String, Object> body = resp.getBody();
        assertThat(((Number) body.get("assetTotal")).longValue()).isEqualTo(1);
        assertThat(((Number) body.get("workspaceTotal")).longValue()).isEqualTo(0);

        var env = c.envMatrix();
        assertThat(env.getBody()).hasSize(4); // 四环境
    }
}
