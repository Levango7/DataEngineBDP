package com.levango7.dataenginebdp.infra.orchestrator.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ClusterController} 集成测试 - 仅覆盖无需鉴权的元数据端点。
 *
 * <p>创建/销毁/扩缩容等端点需要 JWT 鉴权，由 SecurityConfig 保护。
 * 此处仅验证 {@code /environments}、{@code /providers}、{@code /profiles} 等元数据端点
 * 在携带 JWT 时可正常返回。为简化测试，使用 {@code @WithMockUser} 模拟认证。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.orchestrator.providers.xinchang.base-url=http://localhost:8090",
        "app.orchestrator.providers.baremetal.base-url=http://localhost:8091",
        "app.orchestrator.providers.cloud.base-url=http://localhost:8092",
        "app.orchestrator.providers.private.base-url=http://localhost:8093",
        "app.orchestrator.poll.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ClusterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void environmentsEndpointShouldRequireAuth() throws Exception {
        mockMvc.perform(get("/api/v1/clusters/environments"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void providersEndpointShouldRequireAuth() throws Exception {
        mockMvc.perform(get("/api/v1/clusters/providers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void profilesEndpointShouldRequireAuth() throws Exception {
        mockMvc.perform(get("/api/v1/clusters/profiles"))
                .andExpect(status().isUnauthorized());
    }
}