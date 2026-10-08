package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.common.GlobalExceptionHandler;
import com.levango7.dataenginebdp.encaps.quota.QuotaRepository;
import com.levango7.dataenginebdp.encaps.repository.ApiDefinitionRepository;
import com.levango7.dataenginebdp.encaps.repository.AssetRepository;
import com.levango7.dataenginebdp.encaps.repository.DataSourceRepository;
import com.levango7.dataenginebdp.encaps.repository.ProjectRepository;
import com.levango7.dataenginebdp.encaps.repository.SyncTaskRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AdminController} 的租户上下文 fail-closed 契约测试。
 *
 * <p>存在的理由（台账 #56 续十二新增待裁残留）：本类此前在缺租户上下文时抛
 * {@code ResponseStatusException(UNAUTHORIZED)}（→ 401），而
 * {@code AccountController} / {@code ProjectController} / {@code QuotaController} /
 * {@code WorkspaceController} 抛 {@code MissingTenantContextException}（→ 403）。
 * 同一前提、同一服务、两套状态码；而 401 会触发前端 {@code api/client.ts}
 * 清登录态并跳 {@code /login}，把"缺租户上下文"误呈现为"登录过期"、
 * 把用户无故踢出。批次 B（PR #366）统一了字符串租户键，却漏掉了本类。</p>
 *
 * <p>下面两条断言即该语义对齐的回归位：任一被改回 401，本类必须转红。</p>
 */
@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    private static final String TEST_TENANT_ID = "100";

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private QuotaRepository quotaRepository;

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private ApiDefinitionRepository apiRepository;

    @Mock
    private DataSourceRepository dataSourceRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private SyncTaskRepository syncTaskRepository;

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
}
