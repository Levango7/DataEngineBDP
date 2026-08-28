package com.levango7.dataenginebdp.encaps.quota;

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
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * QuotaController MockMvc 测试。
 *
 * <p>使用 standaloneSetup 方式，不依赖 Spring 上下文，直接 mock {@link QuotaService}。</p>
 */
@ExtendWith(MockitoExtension.class)
class QuotaControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private QuotaService quotaService;

    @InjectMocks
    private QuotaController quotaController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(quotaController).build();
    }

    private Quota sampleQuota(Long id) {
        Quota q = new Quota();
        q.setId(id);
        q.setWorkspaceId(10L);
        q.setTenantId(100L);
        q.setCpuLimit("10");
        q.setMemoryLimit("20Gi");
        q.setStorageLimit("100Gi");
        q.setPodLimit("100");
        q.setPvcLimit("50");
        q.setServiceLimit("20");
        q.setMaxCpuPerPod("4");
        q.setMaxMemoryPerPod("8Gi");
        q.setMinCpuPerPod("100m");
        q.setMinMemoryPerPod("256Mi");
        q.setStatus(Quota.QuotaStatus.ACTIVE);
        q.setCreatedAt(LocalDateTime.now());
        q.setUpdatedAt(LocalDateTime.now());
        return q;
    }

    /* ------------------------------ POST /api/v1/quotas ------------------------------ */

    @Test
    @DisplayName("POST /api/v1/quotas — 设置 Quota 返回 201")
    void setQuota_shouldReturn201() throws Exception {
        Quota input = new Quota();
        input.setWorkspaceId(10L);
        input.setTenantId(100L);
        input.setCpuLimit("10");
        input.setMemoryLimit("20Gi");
        input.setStorageLimit("100Gi");
        input.setPodLimit("100");
        input.setPvcLimit("50");
        input.setServiceLimit("20");

        Quota saved = sampleQuota(1L);

        when(quotaService.setQuota(any(Quota.class))).thenReturn(saved);

        mockMvc.perform(post("/api/v1/quotas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.workspaceId").value(10))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("POST /api/v1/quotas — 重复设置返回 409")
    void setQuota_duplicate_shouldReturn409() throws Exception {
        Quota input = new Quota();
        input.setWorkspaceId(10L);
        input.setTenantId(100L);
        input.setCpuLimit("10");
        input.setMemoryLimit("20Gi");
        input.setStorageLimit("100Gi");
        input.setPodLimit("100");
        input.setPvcLimit("50");
        input.setServiceLimit("20");

        when(quotaService.setQuota(any(Quota.class)))
                .thenThrow(new IllegalStateException("Active quota already exists for workspace 10"));

        mockMvc.perform(post("/api/v1/quotas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"));
    }

    /* ------------------------------ GET /api/v1/quotas ------------------------------ */

    @Test
    @DisplayName("GET /api/v1/quotas — 列表返回 200")
    void list_shouldReturn200() throws Exception {
        Quota q1 = sampleQuota(1L);
        Quota q2 = sampleQuota(2L);
        q2.setWorkspaceId(11L);

        when(quotaService.listQuotas(null, null)).thenReturn(List.of(q1, q2));

        mockMvc.perform(get("/api/v1/quotas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("GET /api/v1/quotas?tenantId=100 — 按租户过滤返回 200")
    void list_withTenantId_shouldReturn200() throws Exception {
        Quota q1 = sampleQuota(1L);

        when(quotaService.listQuotas(100L, null)).thenReturn(List.of(q1));

        mockMvc.perform(get("/api/v1/quotas").param("tenantId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].tenantId").value(100));
    }

    @Test
    @DisplayName("GET /api/v1/quotas?workspaceId=10 — 按 Workspace 过滤返回 200")
    void list_withWorkspaceId_shouldReturn200() throws Exception {
        Quota q1 = sampleQuota(1L);

        when(quotaService.listQuotas(null, 10L)).thenReturn(List.of(q1));

        mockMvc.perform(get("/api/v1/quotas").param("workspaceId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].workspaceId").value(10));
    }

    /* ------------------------------ GET /api/v1/quotas/{id} ------------------------------ */

    @Test
    @DisplayName("GET /api/v1/quotas/{id} — 存在时返回 200")
    void get_existingId_shouldReturn200() throws Exception {
        Quota q = sampleQuota(1L);

        when(quotaService.getQuota(1L)).thenReturn(Optional.of(q));

        mockMvc.perform(get("/api/v1/quotas/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/quotas/{id} — 不存在时返回 404")
    void get_nonExistingId_shouldReturn404() throws Exception {
        when(quotaService.getQuota(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/quotas/999"))
                .andExpect(status().isNotFound());
    }

    /* ------------------------------ PUT /api/v1/quotas/{id} ------------------------------ */

    @Test
    @DisplayName("PUT /api/v1/quotas/{id} — 存在时返回 200")
    void update_existingId_shouldReturn200() throws Exception {
        Quota input = new Quota();
        input.setWorkspaceId(10L);
        input.setTenantId(100L);
        input.setCpuLimit("20");
        input.setMemoryLimit("40Gi");
        input.setStorageLimit("200Gi");
        input.setPodLimit("200");
        input.setPvcLimit("100");
        input.setServiceLimit("40");

        Quota updated = sampleQuota(1L);
        updated.setCpuLimit("20");

        when(quotaService.updateQuota(anyLong(), any(Quota.class)))
                .thenReturn(Optional.of(updated));

        mockMvc.perform(put("/api/v1/quotas/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cpuLimit").value("20"));
    }

    @Test
    @DisplayName("PUT /api/v1/quotas/{id} — 不存在时返回 404")
    void update_nonExistingId_shouldReturn404() throws Exception {
        Quota input = new Quota();
        input.setWorkspaceId(10L);
        input.setTenantId(100L);
        input.setCpuLimit("20");
        input.setMemoryLimit("40Gi");
        input.setStorageLimit("200Gi");
        input.setPodLimit("200");
        input.setPvcLimit("100");
        input.setServiceLimit("40");

        when(quotaService.updateQuota(anyLong(), any(Quota.class)))
                .thenReturn(Optional.empty());

        mockMvc.perform(put("/api/v1/quotas/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isNotFound());
    }

    /* ------------------------------ DELETE /api/v1/quotas/{id} ------------------------------ */

    @Test
    @DisplayName("DELETE /api/v1/quotas/{id} — 存在时返回 204")
    void delete_existingId_shouldReturn204() throws Exception {
        when(quotaService.deleteQuota(1L)).thenReturn(true);

        mockMvc.perform(delete("/api/v1/quotas/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /api/v1/quotas/{id} — 不存在时返回 404")
    void delete_nonExistingId_shouldReturn404() throws Exception {
        when(quotaService.deleteQuota(999L)).thenReturn(false);

        mockMvc.perform(delete("/api/v1/quotas/999"))
                .andExpect(status().isNotFound());
    }

    /* ------------------------------ GET /api/v1/quotas/workspace/{id}/usage ------------------------------ */

    @Test
    @DisplayName("GET /api/v1/quotas/workspace/{id}/usage — 返回用量信息")
    void usage_shouldReturnUsage() throws Exception {
        Map<String, Map<String, String>> usage = Map.of(
                "used", Map.of("pods", "5", "requests.cpu", "2"),
                "hard", Map.of("pods", "100", "requests.cpu", "10")
        );
        when(quotaService.getUsage(10L)).thenReturn(usage);

        mockMvc.perform(get("/api/v1/quotas/workspace/10/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.used.pods").value("5"))
                .andExpect(jsonPath("$.hard.pods").value("100"));
    }
}