package com.levango7.dataenginebdp.masterdata.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.masterdata.model.entity.MasterData;
import com.levango7.dataenginebdp.masterdata.service.MasterDataService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
 * MasterDataController REST 契约测试。
 *
 * <p>使用 MockMvc standaloneSetup（不加载 Spring Security FilterChain），
 * mock 掉 {@link MasterDataService}，并挂载 {@link GlobalExceptionHandler} 验证异常映射：</p>
 * <ul>
 *   <li>POST 创建成功返回 201 与实体；缺少 / 空白租户上下文返回 400 且不触达业务层</li>
 *   <li>POST 请求体校验失败返回 400 validation_failed；JSON 不可解析返回 400 malformed_json</li>
 *   <li>GET 分页查询返回 200 与分页结构；PUT 命中 200、未命中 404</li>
 *   <li>DELETE 命中 204、未命中 404</li>
 *   <li>路径变量类型不匹配返回 400 type_mismatch</li>
 * </ul>
 */
@DisplayName("MasterDataController REST 契约")
class MasterDataControllerApiTest {

    private static final String BASE_PATH = "/api/v1/master-data";

    private final MasterDataService masterDataService = mock(MasterDataService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new MasterDataController(masterDataService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("POST 创建成功返回 201 与创建后的记录")
    void createShouldReturn201() throws Exception {
        TenantContext.setTenantId("tenant-A");
        when(masterDataService.create(any(), eq("tenant-A"))).thenReturn(sampleEntity(1L, "tenant-A"));

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelCode\":\"md-org\",\"dataKey\":\"ORG-001\","
                                + "\"dataValue\":\"总部\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.modelCode").value("md-org"))
                .andExpect(jsonPath("$.dataKey").value("ORG-001"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        ArgumentCaptor<com.levango7.dataenginebdp.masterdata.model.dto.CreateMasterDataDTO> captor =
                ArgumentCaptor.forClass(
                        com.levango7.dataenginebdp.masterdata.model.dto.CreateMasterDataDTO.class);
        verify(masterDataService).create(captor.capture(), eq("tenant-A"));
        assertThat(captor.getValue().getModelCode()).isEqualTo("md-org");
        assertThat(captor.getValue().getDataKey()).isEqualTo("ORG-001");
    }

    @Test
    @DisplayName("POST 缺少租户上下文返回 400 且不触达业务层")
    void createShouldReturn400WhenTenantMissing() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelCode\":\"md-org\",\"dataKey\":\"ORG-001\",\"dataValue\":\"总部\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("precondition_failed"))
                .andExpect(jsonPath("$.message").value("缺少租户上下文"));

        verifyNoInteractions(masterDataService);
    }

    @Test
    @DisplayName("POST 空白租户上下文返回 400 且不触达业务层")
    void createShouldReturn400WhenTenantBlank() throws Exception {
        TenantContext.setTenantId("   ");

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelCode\":\"md-org\",\"dataKey\":\"ORG-001\",\"dataValue\":\"总部\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("precondition_failed"));

        verifyNoInteractions(masterDataService);
    }

    @Test
    @DisplayName("POST 请求体校验失败返回 400 validation_failed")
    void createShouldReturn400WhenBodyInvalid() throws Exception {
        TenantContext.setTenantId("tenant-A");

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelCode\":\"\",\"dataKey\":\"ORG-001\",\"dataValue\":\"总部\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("modelCode")));

        verifyNoInteractions(masterDataService);
    }

    @Test
    @DisplayName("POST 请求体 JSON 不可解析返回 400 malformed_json")
    void createShouldReturn400WhenBodyMalformed() throws Exception {
        TenantContext.setTenantId("tenant-A");

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-a-valid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed_json"));

        verifyNoInteractions(masterDataService);
    }

    @Test
    @DisplayName("GET 分页查询返回 200 与分页结构")
    void queryShouldReturn200WithPage() throws Exception {
        TenantContext.setTenantId("tenant-A");
        Page<MasterData> page = new PageImpl<>(List.of(sampleEntity(1L, "tenant-A")),
                PageRequest.of(0, 20), 1);
        when(masterDataService.query(eq("md-org"), eq("tenant-A"), eq(0), eq(20))).thenReturn(page);

        mockMvc.perform(get(BASE_PATH)
                        .param("modelCode", "md-org")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].dataKey").value("ORG-001"));
    }

    @Test
    @DisplayName("GET 缺少租户上下文返回 400 且不触达业务层")
    void queryShouldReturn400WhenTenantMissing() throws Exception {
        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("precondition_failed"));

        verifyNoInteractions(masterDataService);
    }

    @Test
    @DisplayName("PUT 命中时返回 200 与更新后的记录")
    void updateShouldReturn200WhenFound() throws Exception {
        TenantContext.setTenantId("tenant-A");
        MasterData updated = sampleEntity(7L, "tenant-A");
        updated.setDataValue("研发中心");
        when(masterDataService.update(eq(7L), any(), eq("tenant-A"))).thenReturn(updated);

        mockMvc.perform(put(BASE_PATH + "/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataValue\":\"研发中心\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.dataValue").value("研发中心"));
    }

    @Test
    @DisplayName("PUT 未命中时返回 404 且错误码为 master_data_not_found")
    void updateShouldReturn404WhenNotFound() throws Exception {
        TenantContext.setTenantId("tenant-A");
        when(masterDataService.update(eq(7L), any(), eq("tenant-A"))).thenReturn(null);

        mockMvc.perform(put(BASE_PATH + "/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataValue\":\"研发中心\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("master_data_not_found"))
                .andExpect(jsonPath("$.message").value("MasterData 7 not found"));
    }

    @Test
    @DisplayName("PUT 路径变量非数字返回 400 type_mismatch")
    void updateShouldReturn400WhenIdNotNumeric() throws Exception {
        TenantContext.setTenantId("tenant-A");

        mockMvc.perform(put(BASE_PATH + "/not-a-number")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataValue\":\"研发中心\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("type_mismatch"));

        verifyNoInteractions(masterDataService);
    }

    @Test
    @DisplayName("DELETE 命中时返回 204")
    void deleteShouldReturn204WhenRemoved() throws Exception {
        TenantContext.setTenantId("tenant-A");
        when(masterDataService.delete(7L, "tenant-A")).thenReturn(true);

        mockMvc.perform(delete(BASE_PATH + "/7"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE 未命中时返回 404")
    void deleteShouldReturn404WhenNotFound() throws Exception {
        TenantContext.setTenantId("tenant-A");
        when(masterDataService.delete(7L, "tenant-A")).thenReturn(false);

        mockMvc.perform(delete(BASE_PATH + "/7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("master_data_not_found"));
    }

    @Test
    @DisplayName("DELETE 缺少租户上下文返回 400 且不触达业务层")
    void deleteShouldReturn400WhenTenantMissing() throws Exception {
        mockMvc.perform(delete(BASE_PATH + "/7"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("precondition_failed"));

        verifyNoInteractions(masterDataService);
    }

    @Test
    @DisplayName("GET 未传分页参数时使用默认值并透传给业务层")
    void queryShouldForwardDefaultPaging() throws Exception {
        TenantContext.setTenantId("tenant-A");
        Page<MasterData> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(masterDataService.query(any(), eq("tenant-A"), any(), any())).thenReturn(empty);

        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        verify(masterDataService).query(null, "tenant-A", 0, 20);
    }

    /**
     * 构造一条主数据记录响应体。
     */
    private MasterData sampleEntity(Long id, String tenantId) {

        MasterData entity = new MasterData();
        entity.setId(id);
        entity.setModelCode("md-org");
        entity.setDataKey("ORG-001");
        entity.setDataValue("总部");
        entity.setVersion("1.0.0");
        entity.setStatus("ACTIVE");
        entity.setTenantId(tenantId);
        return entity;
    }
}
