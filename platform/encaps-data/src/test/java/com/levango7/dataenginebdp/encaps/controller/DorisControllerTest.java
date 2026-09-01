package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.service.engine.DorisClient;
import com.levango7.dataenginebdp.encaps.service.engine.EngineUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Doris 引擎端点单测（mock DorisClient，不依赖真实 Doris）。
 *
 * <p>覆盖重点：executeQuery 的 SQL 安全校验（危险词拦截 / 只读白名单）、
 * 引擎不可用降级（503）——此前 encaps-data 覆盖率 13%，Doris/Flink/Kafka/
 * IoTDB/Search 五个控制器零测试。</p>
 */
@DisplayName("DorisController 引擎端点与 SQL 安全校验")
class DorisControllerTest {

    private DorisClient dorisClient;
    private DorisController controller;

    @BeforeEach
    void setUp() {
        dorisClient = Mockito.mock(DorisClient.class);
        controller = new DorisController(dorisClient);
        TenantContext.setTenantId("tenant_doris");
        TenantContext.setUserId("tester");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("GET /nodes：正常返回节点列表")
    void listNodes_ok() {
        when(dorisClient.listNodes()).thenReturn(List.of(Map.of("name", "fe-1", "role", "FE")));
        ResponseEntity<?> resp = controller.listNodes();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().toString()).contains("fe-1");
    }

    @Test
    @DisplayName("GET /nodes：引擎不可用返回 503 与错误信息")
    void listNodes_engineUnavailable_503() throws Exception {
        when(dorisClient.listNodes()).thenThrow(new EngineUnavailableException("connect refused"));
        ResponseEntity<?> resp = controller.listNodes();
        assertThat(resp.getStatusCode().value()).isEqualTo(503);
        assertThat(resp.getBody().toString()).contains("Doris 引擎不可用");
    }

    @Test
    @DisplayName("GET /databases/{db}/tables：路径参数透传")
    void listTablesByDb_passesDb() throws Exception {
        when(dorisClient.listTables("sales")).thenReturn(List.of("orders", "users"));
        ResponseEntity<?> resp = controller.listTablesByDb("sales");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().toString()).contains("orders");
    }

    @Test
    @DisplayName("POST /query：SELECT 查询放行并透传")
    void executeQuery_selectAllowed() {
        when(dorisClient.executeQuery("SELECT 1")).thenReturn(Map.of("rows", List.of()));
        ResponseEntity<?> resp = controller.executeQuery(new DorisController.QueryRequest("SELECT 1"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Mockito.verify(dorisClient).executeQuery("SELECT 1");
    }

    @Test
    @DisplayName("POST /query：空 SQL 返回 400")
    void executeQuery_blankSql_400() {
        ResponseEntity<?> resp = controller.executeQuery(new DorisController.QueryRequest("  "));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("POST /query：DROP 语句被拦截（403）")
    void executeQuery_dropBlocked() {
        ResponseEntity<?> resp = controller.executeQuery(new DorisController.QueryRequest("DROP TABLE orders"));
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        Mockito.verifyNoInteractions(dorisClient);
    }

    @Test
    @DisplayName("POST /query：中间含 DELETE 关键词被拦截（如 WITH x AS ... DELETE）")
    void executeQuery_midStatementDeleteBlocked() {
        ResponseEntity<?> resp = controller.executeQuery(
                new DorisController.QueryRequest("WITH t AS (SELECT 1) DELETE FROM t"));
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        Mockito.verifyNoInteractions(dorisClient);
    }

    @Test
    @DisplayName("POST /query：SET 之类非白名单前缀返回 403")
    void executeQuery_nonWhitelistedPrefix_403() {
        ResponseEntity<?> resp = controller.executeQuery(new DorisController.QueryRequest("SET @a=1"));
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("POST /query：SHOW/DESCRIBE/EXPLAIN 白名单放行")
    void executeQuery_showAllowed() {
        when(dorisClient.executeQuery(anyString())).thenReturn(Map.of("rows", List.of()));
        for (String sql : new String[]{"SHOW DATABASES", "DESCRIBE orders", "EXPLAIN SELECT 1"}) {
            ResponseEntity<?> resp = controller.executeQuery(new DorisController.QueryRequest(sql));
            assertThat(resp.getStatusCode().value()).as("SQL: %s", sql).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("POST /query：引擎不可用返回 503")
    void executeQuery_engineUnavailable_503() {
        when(dorisClient.executeQuery("SELECT 1")).thenThrow(new EngineUnavailableException("timeout"));
        ResponseEntity<?> resp = controller.executeQuery(new DorisController.QueryRequest("SELECT 1"));
        assertThat(resp.getStatusCode().value()).isEqualTo(503);
    }

    @Test
    @DisplayName("GET /queries：无历史接口时返回空列表（契约稳定）")
    void listQueries_returnsEmptyList() {
        ResponseEntity<?> resp = controller.listQueries();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) resp.getBody()).isEmpty();
    }
}
