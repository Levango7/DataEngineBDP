package com.shuqing.bigdata.encaps.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shuqing.bigdata.encaps.model.Tenant;
import com.shuqing.bigdata.encaps.service.TenantService;
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
 * TenantController MockMvc 测试。
 *
 * <p>使用 standaloneSetup 方式，不依赖 Spring 上下文。</p>
 */
@ExtendWith(MockitoExtension.class)
class TenantControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private TenantService tenantService;

    @InjectMocks
    private TenantController tenantController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(tenantController).build();
    }

    @Test
    @DisplayName("POST /api/v1/tenants — 创建租户返回201")
    void createTenant_shouldReturn201() throws Exception {
        Tenant input = new Tenant();
        input.setName("tenant-a");
        input.setNamespace("ns-a");

        Tenant saved = new Tenant();
        saved.setId(1L);
        saved.setName("tenant-a");
        saved.setNamespace("ns-a");
        saved.setStatus("ACTIVE");
        saved.setCreatedAt(LocalDateTime.now());
        saved.setUpdatedAt(LocalDateTime.now());

        when(tenantService.create(any(Tenant.class))).thenReturn(saved);

        mockMvc.perform(post("/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("tenant-a"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("GET /api/v1/tenants — 列出全部租户返回200")
    void listTenants_shouldReturn200() throws Exception {
        Tenant t1 = new Tenant();
        t1.setId(1L);
        t1.setName("t1");
        Tenant t2 = new Tenant();
        t2.setId(2L);
        t2.setName("t2");

        when(tenantService.list()).thenReturn(List.of(t1, t2));

        mockMvc.perform(get("/api/v1/tenants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("GET /api/v1/tenants/{id} — 存在时返回200")
    void getTenant_existingId_shouldReturn200() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setId(1L);
        tenant.setName("tenant-x");

        when(tenantService.get(1L)).thenReturn(Optional.of(tenant));

        mockMvc.perform(get("/api/v1/tenants/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("tenant-x"));
    }

    @Test
    @DisplayName("GET /api/v1/tenants/{id} — 不存在时返回404")
    void getTenant_nonExistingId_shouldReturn404() throws Exception {
        when(tenantService.get(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/tenants/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/v1/tenants/{id} — 存在时返回204")
    void deleteTenant_existingId_shouldReturn204() throws Exception {
        when(tenantService.delete(1L)).thenReturn(true);

        mockMvc.perform(delete("/api/v1/tenants/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /api/v1/tenants/{id} — 不存在时返回404")
    void deleteTenant_nonExistingId_shouldReturn404() throws Exception {
        when(tenantService.delete(999L)).thenReturn(false);

        mockMvc.perform(delete("/api/v1/tenants/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/v1/tenants/{id} — 存在时返回200")
    void updateTenant_existingId_shouldReturn200() throws Exception {
        Tenant input = new Tenant();
        input.setName("updated-name");

        Tenant updated = new Tenant();
        updated.setId(1L);
        updated.setName("updated-name");
        updated.setCreatedAt(LocalDateTime.now());
        updated.setUpdatedAt(LocalDateTime.now());

        when(tenantService.update(any(Long.class), any(Tenant.class))).thenReturn(Optional.of(updated));

        mockMvc.perform(put("/api/v1/tenants/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("updated-name"));
    }
}
