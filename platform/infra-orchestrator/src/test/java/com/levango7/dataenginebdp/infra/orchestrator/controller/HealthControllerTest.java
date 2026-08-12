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
 * {@link HealthController} 集成测试。
 *
 * <p>验证 {@code GET /api/v1/health} 端点无需鉴权可访问，返回编排层运行态。</p>
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
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthShouldReturnUpWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("infra-orchestrator"))
                .andExpect(jsonPath("$.layer").value("L0.5"))
                .andExpect(jsonPath("$.totalEnvironments").value(7))
                .andExpect(jsonPath("$.registeredProviders").value(7));
    }
}