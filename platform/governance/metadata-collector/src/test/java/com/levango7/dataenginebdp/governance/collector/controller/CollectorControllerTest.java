package com.levango7.dataenginebdp.governance.collector.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.governance.collector.collector.MetadataCollector;
import com.levango7.dataenginebdp.governance.collector.model.CollectionHistory;
import com.levango7.dataenginebdp.governance.collector.model.CollectionResult;
import com.levango7.dataenginebdp.governance.collector.model.MetadataSource;
import com.levango7.dataenginebdp.governance.collector.repository.MetadataSourceRepository;
import com.levango7.dataenginebdp.governance.collector.service.CollectionSchedulerService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CollectorController MockMvc 测试。
 *
 * <p>使用 Mockito mock Repository 与 Service，验证 REST 端点路由与响应。</p>
 */
@ExtendWith(MockitoExtension.class)
class CollectorControllerTest {

    private static final String TEST_TENANT_ID = "test-tenant";

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private MetadataSourceRepository sourceRepository;
    @Mock
    private CollectionSchedulerService schedulerService;
    @Mock
    private MetadataCollector mockCollector;

    @BeforeEach
    void setUp() {
        // 生产控制器在 R8/R10 加固后 fail-closed：租户上下文缺失直接拒绝。
        // 这里用 standaloneSetup（不挂 JwtAuthFilter），因此需由测试侧显式写入上下文。
        TenantContext.setTenantId(TEST_TENANT_ID);
        TenantContext.setUserId("test-user");
        lenient().when(mockCollector.getType()).thenReturn("HIVE");
        CollectorController controller = new CollectorController(
                sourceRepository, schedulerService, List.of(mockCollector));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("POST /api/v1/metadata/sources — 创建数据源返回 201")
    void addSource_shouldReturnCreated() throws Exception {
        MetadataSource source = new MetadataSource();
        source.setName("hive-prod");
        source.setType("HIVE");
        source.setUrl("jdbc:hive2://localhost:10000/default");

        MetadataSource saved = new MetadataSource();
        saved.setId(1L);
        saved.setName("hive-prod");
        saved.setType("HIVE");
        saved.setUrl("jdbc:hive2://localhost:10000/default");
        saved.setStatus("ACTIVE");
        saved.setCreatedAt(LocalDateTime.now());

        when(sourceRepository.save(any(MetadataSource.class))).thenReturn(saved);

        mockMvc.perform(post("/api/v1/metadata/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(source)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("hive-prod"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("GET /api/v1/metadata/sources — 列出数据源")
    void listSources_shouldReturnList() throws Exception {
        MetadataSource s1 = new MetadataSource();
        s1.setId(1L);
        s1.setName("hive-1");
        s1.setType("HIVE");
        MetadataSource s2 = new MetadataSource();
        s2.setId(2L);
        s2.setName("doris-1");
        s2.setType("DORIS");

        when(sourceRepository.findByTenantId(TEST_TENANT_ID)).thenReturn(List.of(s1, s2));

        mockMvc.perform(get("/api/v1/metadata/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("hive-1"))
                .andExpect(jsonPath("$[1].name").value("doris-1"));
    }

    @Test
    @DisplayName("GET /api/v1/metadata/sources/{id} — 存在返回 200")
    void getSource_exists() throws Exception {
        MetadataSource s = new MetadataSource();
        s.setId(1L);
        s.setName("hive-1");
        s.setType("HIVE");
        when(sourceRepository.findByIdAndTenantId(1L, TEST_TENANT_ID)).thenReturn(Optional.of(s));

        mockMvc.perform(get("/api/v1/metadata/sources/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("hive-1"));
    }

    @Test
    @DisplayName("GET /api/v1/metadata/sources/{id} — 不存在返回 404")
    void getSource_notExists() throws Exception {
        when(sourceRepository.findByIdAndTenantId(999L, TEST_TENANT_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/metadata/sources/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/metadata/collect/{sourceId} — 触发采集返回结果")
    void triggerCollection_shouldReturnResult() throws Exception {
        CollectionResult result = CollectionResult.success(1L, "hive-1", "HIVE");
        result.setTables(List.of());
        result.markFinished();
        when(sourceRepository.findByIdAndTenantId(1L, TEST_TENANT_ID)).thenReturn(Optional.of(new MetadataSource()));
        when(schedulerService.triggerCollection(anyLong(), anyString())).thenReturn(Optional.of(result));

        mockMvc.perform(post("/api/v1/metadata/collect/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.sourceId").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/metadata/collect/{sourceId} — 数据源不存在返回 404")
    void triggerCollection_notFound() throws Exception {
        // 生产先按 (sourceId, tenantId) 校验归属，不存在直接 404，不再走到调度器
        when(sourceRepository.findByIdAndTenantId(999L, TEST_TENANT_ID)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/metadata/collect/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/metadata/collect/status/{sourceId} — 返回最近采集状态")
    void getCollectionStatus_shouldReturnStatus() throws Exception {
        CollectionHistory history = new CollectionHistory();
        history.setId(1L);
        history.setSourceId(1L);
        history.setStatus("SUCCESS");
        history.setStartedAt(LocalDateTime.now());
        when(sourceRepository.findByIdAndTenantId(1L, TEST_TENANT_ID)).thenReturn(Optional.of(new MetadataSource()));
        when(schedulerService.getCollectionStatus(1L)).thenReturn(Optional.of(history));

        mockMvc.perform(get("/api/v1/metadata/collect/status/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("POST /api/v1/metadata/collect/test/{sourceId} — 测试连接成功")
    void testConnection_success() throws Exception {
        MetadataSource s = new MetadataSource();
        s.setId(1L);
        s.setName("hive-1");
        s.setType("HIVE");
        when(sourceRepository.findByIdAndTenantId(1L, TEST_TENANT_ID)).thenReturn(Optional.of(s));
        when(mockCollector.testConnection(any(MetadataSource.class))).thenReturn(true);

        mockMvc.perform(post("/api/v1/metadata/collect/test/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/metadata/collect/test/{sourceId} — 测试连接失败")
    void testConnection_failure() throws Exception {
        MetadataSource s = new MetadataSource();
        s.setId(1L);
        s.setName("hive-1");
        s.setType("HIVE");
        when(sourceRepository.findByIdAndTenantId(1L, TEST_TENANT_ID)).thenReturn(Optional.of(s));
        when(mockCollector.testConnection(any(MetadataSource.class))).thenReturn(false);

        mockMvc.perform(post("/api/v1/metadata/collect/test/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/metadata/collectors — 列出已注册类型")
    void listCollectors_shouldReturnTypes() throws Exception {
        when(schedulerService.getRegisteredTypes()).thenReturn(List.of("HIVE", "DORIS", "KAFKA", "FILESYSTEM"));

        mockMvc.perform(get("/api/v1/metadata/collectors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0]").value("HIVE"));
    }

    @Test
    @DisplayName("GET /api/v1/metadata/sources — 缺租户上下文时 fail-closed（R10 安全语义）")
    void listSources_withoutTenantContext_shouldFailClosed() {
        // 清除上下文，模拟未认证请求绕过 JwtAuthFilter 的极端情况
        TenantContext.clear();

        ServletException ex = assertThrows(ServletException.class, () ->
                mockMvc.perform(get("/api/v1/metadata/sources")));

        assertTrue(ex.getMessage().contains("缺少租户上下文")
                        || ex.getCause() instanceof IllegalStateException,
                "缺租户上下文时应 fail-closed 抛 IllegalStateException，实际: " + ex);
    }
}