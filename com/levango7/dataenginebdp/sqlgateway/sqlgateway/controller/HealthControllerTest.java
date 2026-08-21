package com.shuqing.bigdata.sqlgateway.controller;

import com.shuqing.bigdata.sqlgateway.virtual.DataSourceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HealthController MockMvc 测试。
 *
 * <p>验证重构后基于 {@link com.shuqing.bigdata.common.health.controller.AbstractHealthController}
 * 的统一 {@link com.shuqing.bigdata.common.health.dto.HealthResponse} 响应结构。</p>
 */
class HealthControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<BuildProperties> bpProvider = mock(ObjectProvider.class);
        when(bpProvider.getIfAvailable()).thenReturn(null);
        DataSourceManager dataSourceManager = mock(DataSourceManager.class);
        when(dataSourceManager.getStats()).thenReturn(Map.of());
        HealthController controller = new HealthController(bpProvider, dataSourceManager);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/health — 返回UP状态与统一响应结构")
    void health_shouldReturnUpStatus() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("sql-gateway"))
                .andExpect(jsonPath("$.version").value("unknown"))
                .andExpect(jsonPath("$.details.poolCount").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/health/liveness — 存活探针返回UP")
    void liveness_shouldReturnUp() throws Exception {
        mockMvc.perform(get("/api/v1/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("sql-gateway"));
    }

    @Test
    @DisplayName("GET /api/v1/health/readiness — 就绪探针返回UP与连接池计数")
    void readiness_shouldReturnUpWithPoolCount() throws Exception {
        mockMvc.perform(get("/api/v1/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.details.poolCount").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/health/readiness — 数据源管理器异常时返回DOWN与503")
    @SuppressWarnings("unchecked")
    void readiness_shouldReturnDown503WhenDataSourceManagerThrows() throws Exception {
        ObjectProvider<BuildProperties> bpProvider = mock(ObjectProvider.class);
        when(bpProvider.getIfAvailable()).thenReturn(null);
        DataSourceManager dataSourceManager = mock(DataSourceManager.class);
        when(dataSourceManager.getStats()).thenThrow(new RuntimeException("hikari pool exhausted"));
        HealthController controller = new HealthController(bpProvider, dataSourceManager);
        MockMvc downMockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();

        downMockMvc.perform(get("/api/v1/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.details.error").value("RuntimeException"))
                .andExpect(jsonPath("$.details.message").value("hikari pool exhausted"));
    }

    @Test
    @DisplayName("GET /api/v1/health — Content-Type为JSON")
    void health_shouldReturnJsonContentType() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"));
    }
}
