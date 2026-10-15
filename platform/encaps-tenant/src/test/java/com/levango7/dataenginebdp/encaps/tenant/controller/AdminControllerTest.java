package com.levango7.dataenginebdp.encaps.tenant.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.common.GlobalExceptionHandler;
import com.levango7.dataenginebdp.encaps.quota.Quota;
import com.levango7.dataenginebdp.encaps.quota.QuotaRepository;
import com.levango7.dataenginebdp.encapsshared.repository.ProjectRepository;
import com.levango7.dataenginebdp.encaps.workspace.WorkspaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AdminController} 的租户上下文 fail-closed 契约 + KPI 契约测试。
 *
 * <p><b>一、fail-closed 语义</b>（台账 #56 续十二新增待裁残留）：本类此前在缺租户上下文时抛
 * {@code ResponseStatusException(UNAUTHORIZED)}（→ 401），而
 * {@code AccountController} / {@code ProjectController} / {@code QuotaController} /
 * {@code WorkspaceController} 抛 {@code MissingTenantContextException}（→ 403）。
 * 同一前提、同一服务、两套状态码；而 401 会触发前端 {@code api/client.ts}
 * 清登录态并跳 {@code /login}，把"缺租户上下文"误呈现为"登录过期"、
 * 把用户无故踢出。批次 B（PR #366）统一了字符串租户键，却漏掉了本类。</p>
 *
 * <p>下面两条断言即该语义对齐的回归位：任一被改回 401，本类必须转红。</p>
 *
 * <p><b>二、KPI 契约只含"能诚实算出的数"</b>（2026-10-13 收口）：
 * 此前本端点返回两类编造值，运维会当真实 KPI 读，比返回 {@code null} 更糟 ——</p>
 * <ol>
 *   <li>硬编码：{@code tenantExternal=0} / {@code tenantInternal=1} /
 *       {@code clusterTotal=1} / {@code clusterXinchuang=1}；</li>
 *   <li>结构性恒 0：{@code assetTotal} / {@code apiTotal} /
 *       {@code datasourceTotal} / {@code syncTaskTotal} —— 查询落到本服务
 *       schema 的空副本表。</li>
 * </ol>
 * <p>{@link #kpi_returnsOnlyHonestCounts()} 即该契约的回归位：
 * 这些字段任何一个被加回来，本类必须转红。</p>
 */
@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    private static final String TEST_TENANT_ID = "100";

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private QuotaRepository quotaRepository;

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private AdminController adminController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 生产控制器 tenantId 一律取自 TenantContext（R8 加固后不再从路径/参数收）。
        // standaloneSetup 不挂 JwtAuthFilter，故由测试侧显式写入上下文。
        TenantContext.setTenantId(TEST_TENANT_ID);
        TenantContext.setUserId("test-user");
        // standaloneSetup 不挂 Spring 上下文，故显式注册全局异常处理：
        // MissingTenantContextException 由 GlobalExceptionHandler 映射为 403。
        mockMvc = MockMvcBuilders.standaloneSetup(adminController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("GET /api/v1/admin/kpi — 缺租户上下文时 403（与 Account/Project/Quota/Workspace 同口径，非 401）")
    void kpi_withoutTenantContext_shouldReturn403_not401() throws Exception {
        TenantContext.clear();

        mockMvc.perform(get("/api/v1/admin/kpi"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/admin/env-matrix — 缺租户上下文时 403（同口径，非 401）")
    void envMatrix_withoutTenantContext_shouldReturn403_not401() throws Exception {
        TenantContext.clear();

        mockMvc.perform(get("/api/v1/admin/env-matrix"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/admin/kpi — 非数字租户业务键是合法键，不再被强制 parseLong（台账 #56 方案③）")
    void kpi_nonNumericTenantKey_isAccepted() throws Exception {
        // 与 QuotaControllerTest 的同名回归位对齐：批次 B 把 tenant_id 定为字符串业务键后，
        // "platform-admin" 这类键就是正常输入，不应因非数字而被拒。
        TenantContext.setTenantId("platform-admin");

        mockMvc.perform(get("/api/v1/admin/kpi"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/admin/kpi — 契约只含 3 个诚实计数，编造/读错表字段不得回归（2026-10-13 收口）")
    void kpi_returnsOnlyHonestCounts() throws Exception {
        // 给定：每个仓储返回一个可区分的已知值
        when(workspaceRepository.countByTenantId(anyString())).thenReturn(2L);
        when(quotaRepository.findByTenantId(anyString()))
                .thenReturn(List.of(mock(Quota.class), mock(Quota.class)));
        when(projectRepository.countByTenantId(anyString())).thenReturn(3L);

        mockMvc.perform(get("/api/v1/admin/kpi"))
                .andExpect(status().isOk())
                // 三个能诚实算出的计数，且值是打桩的已知值（不是硬编码）
                .andExpect(jsonPath("$.workspaceTotal").value(2))
                .andExpect(jsonPath("$.quotaTotal").value(2))
                .andExpect(jsonPath("$.projectTotal").value(3))
                // tenantTotal：读的是 encaps_tenant.tenants 孤儿副本（网关把
                // /api/v1/tenants/** 路由到 encaps-layer）⇒ 生产恒空表，不得回归
                .andExpect(jsonPath("$.tenantTotal").doesNotExist())
                // 曾编造的字段一个都不许回来
                .andExpect(jsonPath("$.tenantExternal").doesNotExist())
                .andExpect(jsonPath("$.tenantInternal").doesNotExist())
                .andExpect(jsonPath("$.clusterTotal").doesNotExist())
                .andExpect(jsonPath("$.clusterXinchuang").doesNotExist())
                .andExpect(jsonPath("$.assetTotal").doesNotExist())
                .andExpect(jsonPath("$.apiTotal").doesNotExist())
                .andExpect(jsonPath("$.datasourceTotal").doesNotExist())
                .andExpect(jsonPath("$.syncTaskTotal").doesNotExist());
    }
}
