package com.levango7.dataenginebdp.datastandard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.datastandard.model.dto.CreateStandardDTO;
import com.levango7.dataenginebdp.datastandard.model.dto.UpdateStandardDTO;
import com.levango7.dataenginebdp.datastandard.model.entity.Standard;
import com.levango7.dataenginebdp.datastandard.service.StandardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * StandardController MockMvc 测试（standaloneSetup，Service 层 Mockito 隔离）。
 *
 * <p>覆盖每个端点的成功路径、404 分支，以及租户上下文缺失 / 空白时的 fail-closed 行为
 * 与请求体 @Valid 校验失败路径（经 GlobalExceptionHandler 转 400）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StandardController REST 端点测试")
class StandardControllerTest {

    private static final String TENANT = "tenant-a";

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mvc;

    @Mock
    private StandardService standardService;

    @InjectMocks
    private StandardController standardController;

    @BeforeEach
    void setUp() {
        // 控制器通过 TenantContext 取租户 ID，缺失则 fail-closed，测试需显式设置
        TenantContext.setTenantId(TENANT);
        TenantContext.setUserId("user_test");
        mvc = MockMvcBuilders.standaloneSetup(standardController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("POST /api/v1/standards — 创建成功返回 201 与创建后的标准")
    void createShouldReturn201() throws Exception {
        CreateStandardDTO dto = new CreateStandardDTO();
        dto.setName("客户命名规范");
        dto.setStatus("DRAFT");

        when(standardService.create(any(CreateStandardDTO.class), eq(TENANT)))
                .thenReturn(standard(1L, "客户命名规范"));

        mvc.perform(post("/api/v1/standards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("客户命名规范"));
    }

    @Test
    @DisplayName("POST /api/v1/standards — @Valid 校验失败（name 为空）返回 400 validation_failed")
    void createShouldReturn400WhenValidationFails() throws Exception {
        CreateStandardDTO dto = new CreateStandardDTO();
        dto.setName("");
        dto.setStatus("NOT_A_STATUS");

        mvc.perform(post("/api/v1/standards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(dto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));

        verifyNoInteractions(standardService);
    }

    @Test
    @DisplayName("GET /api/v1/standards — 分页查询返回 200 与分页结果")
    void queryShouldReturn200() throws Exception {
        // 必须显式传入 Pageable：Spring Data 的 Unpaged 在序列化 getPageNumber() 时抛异常
        when(standardService.query(any(), eq(TENANT)))
                .thenReturn(new PageImpl<>(List.of(standard(1L, "客户命名规范")), PageRequest.of(0, 20), 1));

        mvc.perform(get("/api/v1/standards").param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("客户命名规范"));
    }

    @Test
    @DisplayName("GET /api/v1/standards/{id} — 命中返回 200 与标准详情")
    void getByIdShouldReturn200WhenFound() throws Exception {
        when(standardService.getById(1L, TENANT)).thenReturn(standard(1L, "客户命名规范"));

        mvc.perform(get("/api/v1/standards/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("客户命名规范"));
    }

    @Test
    @DisplayName("GET /api/v1/standards/{id} — 未命中返回 404 standard_not_found")
    void getByIdShouldReturn404WhenMissing() throws Exception {
        when(standardService.getById(99L, TENANT)).thenReturn(null);

        mvc.perform(get("/api/v1/standards/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("standard_not_found"))
                .andExpect(jsonPath("$.message").value("Standard 99 not found"));
    }

    @Test
    @DisplayName("PUT /api/v1/standards/{id} — 更新成功返回 200 与更新后的标准")
    void updateShouldReturn200() throws Exception {
        UpdateStandardDTO dto = new UpdateStandardDTO();
        dto.setName("新名称");

        Standard updated = standard(1L, "新名称");
        when(standardService.update(eq(1L), any(UpdateStandardDTO.class), eq(TENANT))).thenReturn(updated);

        mvc.perform(put("/api/v1/standards/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("新名称"));
    }

    @Test
    @DisplayName("PUT /api/v1/standards/{id} — 未命中返回 404 standard_not_found")
    void updateShouldReturn404WhenMissing() throws Exception {
        UpdateStandardDTO dto = new UpdateStandardDTO();
        when(standardService.update(eq(99L), any(UpdateStandardDTO.class), eq(TENANT))).thenReturn(null);

        mvc.perform(put("/api/v1/standards/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(dto)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("standard_not_found"));
    }

    @Test
    @DisplayName("PUT /api/v1/standards/{id} — status 非法值被 @Valid 拒绝，返回 400")
    void updateShouldReturn400WhenStatusInvalid() throws Exception {
        UpdateStandardDTO dto = new UpdateStandardDTO();
        dto.setStatus("ARCHIVED");

        mvc.perform(put("/api/v1/standards/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(dto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.message").value(
                        containsString("DRAFT / PUBLISHED / DEPRECATED")));

        verify(standardService, never()).update(anyLong(), any(UpdateStandardDTO.class), anyString());
    }

    @Test
    @DisplayName("DELETE /api/v1/standards/{id} — 删除成功返回 204")
    void deleteShouldReturn204() throws Exception {
        when(standardService.delete(1L, TENANT)).thenReturn(true);

        mvc.perform(delete("/api/v1/standards/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /api/v1/standards/{id} — 未命中返回 404 standard_not_found")
    void deleteShouldReturn404WhenMissing() throws Exception {
        when(standardService.delete(99L, TENANT)).thenReturn(false);

        mvc.perform(delete("/api/v1/standards/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("standard_not_found"));
    }

    @Test
    @DisplayName("缺少租户上下文时 fail-closed：返回 400 precondition_failed 且不调用服务层")
    void shouldRejectWhenTenantContextMissing() throws Exception {
        TenantContext.clear();

        mvc.perform(get("/api/v1/standards"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("precondition_failed"))
                .andExpect(jsonPath("$.message").value("缺少租户上下文"));

        verifyNoInteractions(standardService);
    }

    @Test
    @DisplayName("租户上下文为空白串时同样 fail-closed：返回 400 precondition_failed")
    void shouldRejectWhenTenantContextBlank() throws Exception {
        TenantContext.setTenantId("   ");

        mvc.perform(get("/api/v1/standards"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("precondition_failed"));

        verifyNoInteractions(standardService);
    }

    private static Standard standard(Long id, String name) {
        Standard s = new Standard();
        s.setId(id);
        s.setName(name);
        s.setStatus("DRAFT");
        s.setVersion("1.0.0");
        s.setTenantId(TENANT);
        s.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        s.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        return s;
    }
}
