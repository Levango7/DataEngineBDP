package com.levango7.dataenginebdp.sqlgateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.sqlgateway.crosssource.CrossSourceException;
import com.levango7.dataenginebdp.sqlgateway.crosssource.CrossSourceExecutor;
import com.levango7.dataenginebdp.sqlgateway.crosssource.MergeResult;
import com.levango7.dataenginebdp.sqlgateway.model.CrossSourceRequest;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect;
import com.levango7.dataenginebdp.sqlgateway.service.SqlRoutingService;
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

import java.util.List;
import java.util.function.BiFunction;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 跨源查询租户隔离（CONVENTIONS §9.5）与 HTTP 错误语义回归测试。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>JWT 上下文与 body 租户不一致 → 403（越权拒绝）；</li>
 *   <li>无认证上下文（单测/内部调用）→ body 租户回退（兼容历史）；</li>
 *   <li>跨源失败按错误码映射 400/502/504（不再 200+FAILED）。</li>
 * </ul>
 *
 * <p>注：CrossSourceExecutor 以手写 fake 注入（Mockito 对该类 mock 受限）。</p>
 */
@ExtendWith(MockitoExtension.class)
class SqlGatewayControllerCrossSourceTenantTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private SqlRoutingService routingService;

    private CrossSourceExecutor crossSourceExecutor;
    private SqlGatewayController controller;

    /** 可替换的 execute 行为：返回结果或抛异常 */
    private BiFunction<String, SqlDialect, MergeResult> executeBehavior;
    private CrossSourceExecutor.ExecutionPlan explainPlan =
            new CrossSourceExecutor.ExecutionPlan(
                    "SELECT 1", "SELECT", List.of("t"), null, List.of("trino"), false, "single", 0L);

    @BeforeEach
    void setUp() {
        crossSourceExecutor = new CrossSourceExecutor(new com.levango7.dataenginebdp.sqlgateway.parser.SqlParserService()) {
            @Override
            public MergeResult execute(String sql, SqlDialect dialect, String tenantId) {
                // 忽略 tenantId：fake 仅用于验证 Controller 的租户裁决与状态码映射
                return executeBehavior.apply(sql, dialect);
            }

            @Override
            public CrossSourceExecutor.ExecutionPlan explain(String sql, SqlDialect dialect) {
                return explainPlan;
            }
        };
        controller = new SqlGatewayController(routingService, crossSourceExecutor);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private CrossSourceRequest buildRequest(String sql, String tenantId) {
        CrossSourceRequest request = new CrossSourceRequest();
        request.setSql(sql);
        request.setDialect("ANSI");
        request.setTenantId(tenantId);
        return request;
    }

    @Test
    @DisplayName("JWT 租户与 body 租户不一致 → 403")
    void crossSourceExecute_mismatchTenant_returns403() throws Exception {
        TenantContext.setTenantId("tenant-A");

        mockMvc.perform(post("/api/v1/sql/cross-source")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest("SELECT 1", "tenant-B"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("tenant_mismatch")));
    }

    @Test
    @DisplayName("无认证上下文 → body 租户回退，查询成功返回 200")
    void crossSourceExecute_noAuthContext_usesBodyTenant() throws Exception {
        MergeResult result = new MergeResult();
        result.setColumns(List.of("c1"));
        result.addRow(List.of("v1"));
        result.setSource("trino");
        executeBehavior = (sql, dialect) -> result;

        mockMvc.perform(post("/api/v1/sql/cross-source")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest("SELECT 1", "tenant-001"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.rowCount").value(1));
    }

    @Test
    @DisplayName("跨源上游执行失败 → 502（不再 200+FAILED）")
    void crossSourceExecute_upstreamFailure_returns502() throws Exception {
        TenantContext.setTenantId("tenant-A");
        executeBehavior = (sql, dialect) -> {
            throw new CrossSourceException(CrossSourceException.QUERY_FAILED, "trino 连接失败");
        };

        mockMvc.perform(post("/api/v1/sql/cross-source")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest("SELECT 1", "tenant-A"))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("QUERY_FAILED")));
    }

    @Test
    @DisplayName("SQL 解析失败 → 400")
    void crossSourceExecute_parseError_returns400() throws Exception {
        TenantContext.setTenantId("tenant-A");
        executeBehavior = (sql, dialect) -> {
            throw new CrossSourceException(CrossSourceException.PARSE_ERROR, "无法解析 SQL");
        };

        mockMvc.perform(post("/api/v1/sql/cross-source")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest("SELEC 1", "tenant-A"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }
}
