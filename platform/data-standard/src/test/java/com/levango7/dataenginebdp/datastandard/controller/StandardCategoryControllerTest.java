package com.levango7.dataenginebdp.datastandard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.datastandard.model.entity.StandardCategory;
import com.levango7.dataenginebdp.datastandard.service.StandardCategoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * StandardCategoryController MockMvc 测试（standaloneSetup，Service 层 Mockito 隔离）。
 *
 * <p>覆盖分类列表与创建端点，以及租户上下文缺失时的 fail-closed 行为。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StandardCategoryController REST 端点测试")
class StandardCategoryControllerTest {

    private static final String TENANT = "tenant-a";

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mvc;

    @Mock
    private StandardCategoryService categoryService;

    @InjectMocks
    private StandardCategoryController categoryController;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT);
        TenantContext.setUserId("user_test");
        mvc = MockMvcBuilders.standaloneSetup(categoryController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("GET /api/v1/standard-categories — 返回 200 与该租户的分类列表")
    void listAllShouldReturn200() throws Exception {
        when(categoryService.listAll(TENANT))
                .thenReturn(List.of(category(1L, "用户域", "user-domain")));

        mvc.perform(get("/api/v1/standard-categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("user-domain"));
    }

    @Test
    @DisplayName("POST /api/v1/standard-categories — 创建成功返回 201 与创建后的分类")
    void createShouldReturn201() throws Exception {
        StandardCategory request = new StandardCategory();
        request.setName("订单域");
        request.setCode("order-domain");

        when(categoryService.create(any(StandardCategory.class), eq(TENANT)))
                .thenReturn(category(2L, "订单域", "order-domain"));

        mvc.perform(post("/api/v1/standard-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.code").value("order-domain"));

        ArgumentCaptor<StandardCategory> captor = ArgumentCaptor.forClass(StandardCategory.class);
        verify(categoryService).create(captor.capture(), eq(TENANT));
        assertThat(captor.getValue().getName()).isEqualTo("订单域");
        assertThat(captor.getValue().getCode()).isEqualTo("order-domain");
    }

    @Test
    @DisplayName("缺少租户上下文时 fail-closed：返回 400 precondition_failed 且不调用服务层")
    void shouldRejectWhenTenantContextMissing() throws Exception {
        TenantContext.clear();

        mvc.perform(get("/api/v1/standard-categories"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("precondition_failed"))
                .andExpect(jsonPath("$.message").value("缺少租户上下文"));

        verifyNoInteractions(categoryService);
    }

    private static StandardCategory category(Long id, String name, String code) {
        StandardCategory c = new StandardCategory();
        c.setId(id);
        c.setName(name);
        c.setCode(code);
        c.setTenantId(TENANT);
        return c;
    }
}
