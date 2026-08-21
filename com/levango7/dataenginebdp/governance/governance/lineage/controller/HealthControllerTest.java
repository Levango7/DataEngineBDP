package com.shuqing.bigdata.governance.lineage.controller;

import com.shuqing.bigdata.governance.lineage.service.LineageGraphWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HealthController MockMvc 测试。
 *
 * <p>验证重构后基于 {@link com.shuqing.bigdata.common.health.controller.AbstractHealthController}
 * 的统一 {@link com.shuqing.bigdata.common.health.dto.HealthResponse} 响应结构。</p>
 *
 * <p>覆盖场景：</p>
 * <ul>
 *   <li>GET /api/v1/health — 向后兼容端点，图谱写入器正常时返回 UP。</li>
 *   <li>GET /api/v1/health/liveness — 存活探针，始终返回 UP。</li>
 *   <li>GET /api/v1/health/readiness — 就绪探针，图谱写入器正常返回 UP，异常返回 DOWN + 503。</li>
 * </ul>
 */
class HealthControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<BuildProperties> bpProvider = mock(ObjectProvider.class);
        when(bpProvider.getIfAvailable()).thenReturn(null);
        LineageGraphWriter graphWriter = mock(LineageGraphWriter.class);
        when(graphWriter.getKnownTables()).thenReturn(Set.of("t1", "t2"));
        HealthController controller = new HealthController(bpProvider, graphWriter);
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
                .andExpect(jsonPath("$.service").value("lineage-analyzer"))
                .andExpect(jsonPath("$.version").value("unknown"))
                .andExpect(jsonPath("$.details.knownTables").value(2));
    }

    @Test
    @DisplayName("GET /api/v1/health/liveness — 存活探针返回UP")
    void liveness_shouldReturnUp() throws Exception {
        mockMvc.perform(get("/api/v1/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("lineage-analyzer"));
    }

    @Test
    @DisplayName("GET /api/v1/health/readiness — 图谱写入器正常时返回UP与已知表计数")
    void readiness_shouldReturnUpWithKnownTables() throws Exception {
        mockMvc.perform(get("/api/v1/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.details.knownTables").value(2));
    }

    @Test
    @DisplayName("GET /api/v1/health/readiness — 图谱写入器异常时返回DOWN与503")
    @SuppressWarnings("unchecked")
    void readiness_shouldReturnDown503WhenGraphWriterThrows() throws Exception {
        ObjectProvider<BuildProperties> bpProvider = mock(ObjectProvider.class);
        when(bpProvider.getIfAvailable()).thenReturn(null);
        LineageGraphWriter graphWriter = mock(LineageGraphWriter.class);
        when(graphWriter.getKnownTables()).thenThrow(new RuntimeException("graph store unreachable"));
        HealthController controller = new HealthController(bpProvider, graphWriter);
        MockMvc downMockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();

        downMockMvc.perform(get("/api/v1/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.details.error").value("RuntimeException"));
    }

    @Test
    @DisplayName("GET /api/v1/health — Content-Type为JSON")
    void health_shouldReturnJsonContentType() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"));
    }
}