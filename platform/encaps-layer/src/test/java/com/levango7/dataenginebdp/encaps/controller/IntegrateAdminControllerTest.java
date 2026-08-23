package com.levango7.dataenginebdp.encaps.controller;

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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integrate 端点单测（H2 + 真实 Repository）。
 *
 * <p>注：原 {@code admin_kpiAggregatesRealCounts} 用例依赖 {@code AdminController}，
 * 而 {@code AdminController} / {@code WorkspaceRepository} / {@code QuotaRepository}
 * 均位于 encaps-tenant 模块。由于 encaps-layer 不依赖 encaps-tenant（encaps-tenant → encaps-layer
 * 为单向依赖，反向添加会形成循环），此处不可引用上述类型，否则编译失败。
 * Admin 相关用例应放在 encaps-tenant 模块的测试中执行，故从此处移除。</p>
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class IntegrateAdminControllerTest {

    @Autowired private SyncTaskRepository syncTaskRepository;

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
}
