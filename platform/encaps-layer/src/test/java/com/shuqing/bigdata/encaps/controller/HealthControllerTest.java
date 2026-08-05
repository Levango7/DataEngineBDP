package com.shuqing.bigdata.encaps.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HealthController MockMvc 测试。
 */
class HealthControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController()).build();
    }

    @Test
    @DisplayName("GET /api/v1/health — 返回UP状态")
    void health_shouldReturnUpStatus() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.component").value("encaps-layer"))
                .andExpect(jsonPath("$.version").value("0.1.0"));
    }

    @Test
    @DisplayName("GET /api/v1/health — 响应包含所有必要字段")
    void health_shouldContainAllFields() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.component").exists())
                .andExpect(jsonPath("$.version").exists());
    }

    @Test
    @DisplayName("GET /api/v1/health — Content-Type为JSON")
    void health_shouldReturnJsonContentType() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"));
    }
}
