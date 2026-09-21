package com.levango7.dataenginebdp.encaps.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.common.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WorkspaceController MockMvc 测试。
 *
 * <p>使用 standaloneSetup 方式，不依赖 Spring 上下文，直接 mock {@link WorkspaceService}。</p>
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceControllerTest {

    /** 与 {@code sampleWorkspace} 中的 tenantId 保持一致（控制器从 TenantContext 取租户并校验归属）。 */
    private static final String TEST_TENANT_ID = "100";

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private WorkspaceService workspaceService;

    @InjectMocks
    private WorkspaceController workspaceController;

    @BeforeEach
    void setUp() {
        // 生产控制器在 R8 加固后 tenantId 一律取自 TenantContext，缺失返回 401。
        // standaloneSetup 不挂 JwtAuthFilter，故由测试侧显式写入上下文。
        TenantContext.setTenantId(TEST_TENANT_ID);
        TenantContext.setUserId("test-user");
        mockMvc = MockMvcBuilders.standaloneSetup(workspaceController).build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Workspace sampleWorkspace(Long id, String name) {
        Workspace ws = new Workspace();
        ws.setId(id);
        ws.setName(name);
        ws.setTenantId(100L);
        ws.setNamespace("ws-100-" + name);
        ws.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        ws.setCreatedAt(LocalDateTime.now());
        ws.setUpdatedAt(LocalDateTime.now());
        return ws;
    }

    @Test
    @DisplayName("POST /api/v1/workspaces — 创建 Workspace 返回 201")
    void create_shouldReturn201() throws Exception {
        Workspace input = new Workspace();
        input.setName("new-ws");
        input.setTenantId(100L);

        Workspace saved = sampleWorkspace(1L, "new-ws");

        when(workspaceService.createWorkspace(any(Workspace.class))).thenReturn(saved);

        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("new-ws"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("GET /api/v1/workspaces — 列表返回 200")
    void list_shouldReturn200() throws Exception {
        Workspace w1 = sampleWorkspace(1L, "ws-1");
        Workspace w2 = sampleWorkspace(2L, "ws-2");

        when(workspaceService.listWorkspaces(100L)).thenReturn(List.of(w1, w2));

        // 分页契约：返回 {list,total,page,size}（对齐前端 PagedResult）
        mockMvc.perform(get("/api/v1/workspaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list.length()").value(2))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/workspaces?tenantId=100 — 按租户过滤返回 200")
    void list_withTenantId_shouldReturn200() throws Exception {
        Workspace w1 = sampleWorkspace(1L, "ws-1");

        when(workspaceService.listWorkspaces(100L)).thenReturn(List.of(w1));

        mockMvc.perform(get("/api/v1/workspaces").param("tenantId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list.length()").value(1))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].tenantId").value(100));
    }

    @Test
    @DisplayName("GET /api/v1/workspaces/{id} — 存在时返回 200")
    void get_existingId_shouldReturn200() throws Exception {
        Workspace ws = sampleWorkspace(1L, "found");

        when(workspaceService.getWorkspace(1L)).thenReturn(Optional.of(ws));

        mockMvc.perform(get("/api/v1/workspaces/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("found"));
    }

    @Test
    @DisplayName("GET /api/v1/workspaces/{id} — 不存在时返回 404")
    void get_nonExistingId_shouldReturn404() throws Exception {
        when(workspaceService.getWorkspace(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/workspaces/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/v1/workspaces/{id} — 存在时返回 200")
    void update_existingId_shouldReturn200() throws Exception {
        Workspace input = new Workspace();
        input.setName("updated-name");
        input.setTenantId(100L);

        Workspace updated = sampleWorkspace(1L, "updated-name");

        when(workspaceService.getWorkspace(1L)).thenReturn(Optional.of(sampleWorkspace(1L, "old-name")));
        when(workspaceService.updateWorkspace(any(Long.class), any(Workspace.class)))
                .thenReturn(Optional.of(updated));

        mockMvc.perform(put("/api/v1/workspaces/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("updated-name"));
    }

    @Test
    @DisplayName("PUT /api/v1/workspaces/{id} — 不存在时返回 404")
    void update_nonExistingId_shouldReturn404() throws Exception {
        Workspace input = new Workspace();
        input.setName("some-name");
        input.setTenantId(100L);

        // 生产先按 (id, tenantId) 校验归属，不存在直接 404，不再走到 update
        when(workspaceService.getWorkspace(999L)).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/v1/workspaces/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/v1/workspaces/{id} — 存在时返回 204")
    void delete_existingId_shouldReturn204() throws Exception {
        when(workspaceService.getWorkspace(1L)).thenReturn(Optional.of(sampleWorkspace(1L, "ws-1")));
        when(workspaceService.deleteWorkspace(1L)).thenReturn(true);

        mockMvc.perform(delete("/api/v1/workspaces/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /api/v1/workspaces/{id} — 不存在时返回 404")
    void delete_nonExistingId_shouldReturn404() throws Exception {
        // 生产先按 (id, tenantId) 校验归属，不存在直接 404，不再走到 delete
        when(workspaceService.getWorkspace(999L)).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/v1/workspaces/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/workspaces/{id}/status — 返回 K8s Namespace 状态")
    void status_shouldReturnK8sStatus() throws Exception {
        when(workspaceService.getWorkspace(1L)).thenReturn(Optional.of(sampleWorkspace(1L, "ws-1")));
        when(workspaceService.getK8sStatus(1L)).thenReturn("Active");

        mockMvc.perform(get("/api/v1/workspaces/1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"));
    }

    @Test
    @DisplayName("GET /api/v1/workspaces/{id}/status — Workspace 不存在时返回 NotFound")
    void status_nonExisting_shouldReturnNotFound() throws Exception {
        // Workspace 存在且属于当前租户，但底层 K8s Namespace 已不存在
        when(workspaceService.getWorkspace(999L)).thenReturn(Optional.of(sampleWorkspace(999L, "gone")));
        when(workspaceService.getK8sStatus(999L)).thenReturn("NotFound");

        mockMvc.perform(get("/api/v1/workspaces/999/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NotFound"));
    }

    @Test
    @DisplayName("GET /api/v1/workspaces/{id} — 缺租户上下文时 fail-closed 返回 401（R8 安全语义）")
    void get_withoutTenantContext_shouldReturn401() throws Exception {
        TenantContext.clear();

        mockMvc.perform(get("/api/v1/workspaces/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/workspaces/{id} — 跨租户访问返回 404（R8 租户隔离）")
    void get_otherTenantWorkspace_shouldReturn404() throws Exception {
        Workspace other = sampleWorkspace(1L, "other-tenant-ws");
        other.setTenantId(999L);

        when(workspaceService.getWorkspace(1L)).thenReturn(Optional.of(other));

        mockMvc.perform(get("/api/v1/workspaces/1"))
                .andExpect(status().isNotFound());
    }
}