package com.levango7.dataenginebdp.masterdata;

import com.levango7.dataenginebdp.common.security.JwtAuthFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * master-data 完整 Spring 上下文启动 + 模块级过滤链装配的实证测试。
 *
 * <p>本测试的存在意义（P0 回归防护）：模块级 {@code SecurityConfig#securityFilterChain} 依赖
 * 注入 {@link JwtAuthFilter}，而该 Bean 原本由 {@code common-security} 的
 * {@code SecurityConfig} 提供——后者被类级
 * {@code @ConditionalOnMissingBean(SecurityFilterChain.class)} 守卫，业务模块一旦自定义
 * 过滤链就会整类退让，导致 JwtAuthFilter 缺失、应用无法启动（实测：修复前本类 6 个用例
 * 全部 {@code No qualifying bean of type 'JwtAuthFilter'}）。修复后该 Bean 由独立的
 * {@code JwtAuthFilterAutoConfiguration} 提供，不再随过滤链退让。</p>
 *
 * <p>验证点：</p>
 * <ul>
 *   <li>上下文可启动，且 JwtAuthFilter Bean 可用（修复前此处直接失败）</li>
 *   <li>合法 API Key 的写请求返回 <b>201</b>，记录归属配置租户并可查回（P0-2 回归）</li>
 *   <li>过滤链已生效：匿名请求 401、非法 API Key 401 且错误体来自 ApiKeyAuthFilter</li>
 *   <li>/actuator/health 无需认证即可访问</li>
 * </ul>
 *
 * <p>使用 {@code @Transactional} 回滚写操作，避免用例之间互相污染。</p>
 *
 * <p><b>P0-2 端到端回归</b>：过滤链顺序为 {@code RateLimit → ApiKeyAuth → JwtAuth}。
 * 修复前 {@link JwtAuthFilter} 不检查上游是否已认证，只看 {@code Authorization} 头，
 * 会把合法的 API Key 请求全部 401（应用此前根本起不来，这条路径从未被执行过）。现已修复：
 * 上游已建立真实认证时 JwtAuthFilter 跳过。因此此处必须断言写请求返回 <b>201</b>，
 * 而不只是「不再 401」。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("master-data 上下文启动与过滤链装配")
class MasterDataContextBootTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("上下文可启动且 JwtAuthFilter Bean 可用")
    void contextShouldProvideJwtAuthFilterBean() {
        assertThat(webApplicationContext).isNotNull();
        assertThat(webApplicationContext.getBeanNamesForType(JwtAuthFilter.class))
                .as("模块级 SecurityConfig 注入依赖的 JwtAuthFilter 必须可用")
                .isNotEmpty();
        assertThat(webApplicationContext.getBean(JwtAuthFilter.class)).isNotNull();
    }

    @Test
    @DisplayName("P0-2：合法 API Key 创建主数据返回 201，记录归属配置租户并可查回")
    void shouldCreateAndQueryWithValidApiKey() throws Exception {
        mockMvc.perform(post("/api/v1/master-data")
                        .header("X-API-Key", "test-api-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelCode\":\"md-org\",\"dataKey\":\"ORG-001\","
                                + "\"dataValue\":\"总部\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.modelCode").value("md-org"))
                .andExpect(jsonPath("$.tenantId").value("test-tenant"))
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/v1/master-data")
                        .header("X-API-Key", "test-api-key-1")
                        .param("modelCode", "md-org"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].dataKey").value("ORG-001"))
                .andExpect(jsonPath("$.content[0].tenantId").value("test-tenant"));
    }

    @Test
    @DisplayName("P0-2：合法 API Key 创建主数据模型返回 201，并可列表查回")
    void shouldCreateModelWithValidApiKey() throws Exception {
        mockMvc.perform(post("/api/v1/master-data/models")
                        .header("X-API-Key", "test-api-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"md-org\",\"name\":\"组织\",\"fieldsSchema\":\"[]\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("md-org"))
                .andExpect(jsonPath("$.tenantId").value("test-tenant"));

        mockMvc.perform(get("/api/v1/master-data/models")
                        .header("X-API-Key", "test-api-key-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("md-org"));
    }

    @Test
    @DisplayName("P0-2：合法 API Key 删除记录返回 204")
    void shouldDeleteWithValidApiKey() throws Exception {
        String created = mockMvc.perform(post("/api/v1/master-data")
                        .header("X-API-Key", "test-api-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelCode\":\"md-org\",\"dataKey\":\"ORG-DEL\","
                                + "\"dataValue\":\"待删除\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mockMvc.perform(delete("/api/v1/master-data/" + id)
                        .header("X-API-Key", "test-api-key-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("过滤链已生效：匿名请求返回 401")
    void shouldRejectAnonymousRequest() throws Exception {
        mockMvc.perform(get("/api/v1/master-data"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("过滤链已生效：非法 API Key 返回 401 且错误体来自 ApiKeyAuthFilter")
    void shouldRejectInvalidApiKey() throws Exception {
        mockMvc.perform(get("/api/v1/master-data")
                        .header("X-API-Key", "not-a-valid-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid API key"));
    }

    @Test
    @DisplayName("actuator health 无需认证即可访问")
    void shouldAllowActuatorHealth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
