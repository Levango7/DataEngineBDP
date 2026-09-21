package com.levango7.dataenginebdp.masterdata.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.masterdata.model.entity.MasterDataModel;
import com.levango7.dataenginebdp.masterdata.service.MasterDataModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MasterDataModelController REST 契约测试。
 *
 * <p>使用 MockMvc standaloneSetup（不加载 Spring Security FilterChain），
 * mock 掉 {@link MasterDataModelService}，并挂载 {@link GlobalExceptionHandler} 验证异常映射：</p>
 * <ul>
 *   <li>GET 列出模型返回 200 与模型数组</li>
 *   <li>POST 创建成功返回 201 与模型实体</li>
 *   <li>缺少 / 空白租户上下文返回 400 且不触达业务层</li>
 *   <li>请求体校验失败返回 400 validation_failed</li>
 * </ul>
 */
@DisplayName("MasterDataModelController REST 契约")
class MasterDataModelControllerApiTest {

    private static final String BASE_PATH = "/api/v1/master-data/models";

    private final MasterDataModelService modelService = mock(MasterDataModelService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new MasterDataModelController(modelService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("GET 列出该租户下的全部模型")
    void listAllShouldReturn200WithModels() throws Exception {
        TenantContext.setTenantId("tenant-A");
        MasterDataModel model = new MasterDataModel();
        model.setId(1L);
        model.setCode("md-org");
        model.setName("组织");
        when(modelService.listAll("tenant-A")).thenReturn(List.of(model));

        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].code").value("md-org"))
                .andExpect(jsonPath("$[0].name").value("组织"));
    }

    @Test
    @DisplayName("GET 缺少租户上下文返回 400 且不触达业务层")
    void listAllShouldReturn400WhenTenantMissing() throws Exception {
        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("precondition_failed"));

        verifyNoInteractions(modelService);
    }

    @Test
    @DisplayName("GET 空白租户上下文返回 400 且不触达业务层")
    void listAllShouldReturn400WhenTenantBlank() throws Exception {
        TenantContext.setTenantId(" ");

        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("precondition_failed"));

        verifyNoInteractions(modelService);
    }

    @Test
    @DisplayName("POST 创建成功返回 201 与创建后的模型")
    void createShouldReturn201() throws Exception {
        TenantContext.setTenantId("tenant-A");
        MasterDataModel created = new MasterDataModel();
        created.setId(3L);
        created.setCode("md-customer");
        created.setName("客户");
        when(modelService.create(any(), eq("tenant-A"))).thenReturn(created);

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"md-customer\",\"name\":\"客户\","
                                + "\"fieldsSchema\":\"[]\",\"description\":\"客户主数据\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.code").value("md-customer"))
                .andExpect(jsonPath("$.name").value("客户"));

        ArgumentCaptor<com.levango7.dataenginebdp.masterdata.model.dto.CreateMasterDataModelDTO> captor =
                ArgumentCaptor.forClass(
                        com.levango7.dataenginebdp.masterdata.model.dto.CreateMasterDataModelDTO.class);
        verify(modelService).create(captor.capture(), eq("tenant-A"));
        assertThat(captor.getValue().getCode()).isEqualTo("md-customer");
        assertThat(captor.getValue().getFieldsSchema()).isEqualTo("[]");
    }

    @Test
    @DisplayName("POST 缺少租户上下文返回 400 且不触达业务层")
    void createShouldReturn400WhenTenantMissing() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"md-customer\",\"name\":\"客户\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("precondition_failed"));

        verifyNoInteractions(modelService);
    }

    @Test
    @DisplayName("POST 请求体校验失败返回 400 validation_failed")
    void createShouldReturn400WhenBodyInvalid() throws Exception {
        TenantContext.setTenantId("tenant-A");

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\",\"name\":\"客户\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("code")));

        verifyNoInteractions(modelService);
    }
}
