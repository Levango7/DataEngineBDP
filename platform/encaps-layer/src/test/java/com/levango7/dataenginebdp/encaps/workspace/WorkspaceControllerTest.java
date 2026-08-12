package com.levango7.dataenginebdp.encaps.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private WorkspaceService workspaceService;

    @InjectMocks
    private WorkspaceController workspaceController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(workspaceController).build();
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

        when(workspaceService.listWorkspaces(null)).thenReturn(List.of(w1, w2));

        mockMvc.perform(get("/api/v1/workspaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("GET /api/v1/workspaces?tenantId=100 — 按租户过滤返回 200")
    void list_withTenantId_shouldReturn200() throws Exception {
        Workspace w1 = sampleWorkspace(1L, "ws-1");

        when(workspaceService.listWorkspaces(100L)).thenReturn(List.of(w1));

        mockMvc.perform(get("/api/v1/workspaces").param("tenantId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].tenantId").value(100));
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

        when(workspaceService.updateWorkspace(any(Long.class), any(Workspace.class)))
                .thenReturn(Optional.empty());

        mockMvc.perform(put("/api/v1/workspaces/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/v1/workspaces/{id} — 存在时返回 204")
    void delete_existingId_shouldReturn204() throws Exception {
        when(workspaceService.deleteWorkspace(1L)).thenReturn(true);

        mockMvc.perform(delete("/api/v1/workspaces/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /api/v1/workspaces/{id} — 不存在时返回 404")
    void delete_nonExistingId_shouldReturn404() throws Exception {
        when(workspaceService.deleteWorkspace(999L)).thenReturn(false);

        mockMvc.perform(delete("/api/v1/workspaces/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/workspaces/{id}/status — 返回 K8s Namespace 状态")
    void status_shouldReturnK8sStatus() throws Exception {
        when(workspaceService.getK8sStatus(1L)).thenReturn("Active");

        mockMvc.perform(get("/api/v1/workspaces/1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"));
    }

    @Test
    @DisplayName("GET /api/v1/workspaces/{id}/status — Workspace 不存在时返回 NotFound")
    void status_nonExisting_shouldReturnNotFound() throws Exception {
        when(workspaceService.getK8sStatus(999L)).thenReturn("NotFound");

        mockMvc.perform(get("/api/v1/workspaces/999/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NotFound"));
    }
}