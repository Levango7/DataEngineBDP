package com.levango7.dataenginebdp.governance.lineage.controller;

import com.levango7.dataenginebdp.governance.lineage.service.LineageAnalyzerService;
import com.levango7.dataenginebdp.governance.lineage.service.LineageGraphWriter;
import com.levango7.dataenginebdp.governance.lineage.service.LineageQueryService;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LineageController} REST API 集成测试。
 *
 * @author shuqing-bigdata
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
@DisplayName("血缘 REST API 测试")
class LineageControllerTest {

    @LocalServerPort
    private int port;

    /** Spring Boot 4：TestRestTemplate 已移除，用 Spring 7 原生 RestClient（阻塞语义等价）。 */
    private RestClient restClient;

    @Autowired
    private LineageGraphWriter graphWriter;

    @Autowired
    private LineageAnalyzerService analyzerService;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        graphWriter.clear();
        // context-path=/lineage 已移除，API 直接从 /api/v1/* 访问
        baseUrl = "http://localhost:" + port + "/api/v1/lineage";
        restClient = RestClient.create();
    }

    @Test
    @DisplayName("POST /analyze 返回 ECharts 格式")
    void testAnalyzeEndpoint() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sql", "INSERT INTO b SELECT x FROM a");
        body.put("dialect", "ANSI");

        ResponseEntity<Map> resp = restClient.post()
                .uri(baseUrl + "/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(Map.class);
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        assertNotNull(resp.getBody());
        Map result = resp.getBody();
        assertNotNull(result.get("nodes"));
        assertNotNull(result.get("links"));
        assertNotNull(result.get("meta"));
    }

    @Test
    @DisplayName("POST /analyze 空 SQL 返回 400")
    void testAnalyzeEmptySql() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sql", "");

        // RestClient 的 retrieve() 对 4xx 抛异常；用 onStatus 捕获校验状态码
        final int[] capturedStatus = {0};
        restClient.post()
                .uri(baseUrl + "/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> status.is4xxClientError(),
                        (req, resp) -> capturedStatus[0] = resp.getStatusCode().value())
                .toEntity(Map.class);
        assertTrue(capturedStatus[0] > 0, "应返回 4xx，实际: " + capturedStatus[0]);
    }

    @Test
    @DisplayName("GET /upstream/{table} 返回上游")
    void testUpstreamEndpoint() {
        // 构造 a → b
        analyzerService.analyze("INSERT INTO b SELECT x FROM a", SqlDialect.ANSI);
        ResponseEntity<Map> resp = restClient.get()
                .uri(baseUrl + "/upstream/b")
                .retrieve()
                .toEntity(Map.class);
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        assertNotNull(resp.getBody());
    }

    @Test
    @DisplayName("GET /downstream/{table} 返回下游")
    void testDownstreamEndpoint() {
        analyzerService.analyze("INSERT INTO b SELECT x FROM a", SqlDialect.ANSI);
        ResponseEntity<Map> resp = restClient.get()
                .uri(baseUrl + "/downstream/a")
                .retrieve()
                .toEntity(Map.class);
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        assertNotNull(resp.getBody());
    }

    @Test
    @DisplayName("GET /impact/{table} 影响分析")
    void testImpactEndpoint() {
        analyzerService.analyze("INSERT INTO b SELECT x FROM a", SqlDialect.ANSI);
        analyzerService.analyze("INSERT INTO c SELECT x FROM b", SqlDialect.ANSI);
        ResponseEntity<Map> resp = restClient.get()
                .uri(baseUrl + "/impact/a")
                .retrieve()
                .toEntity(Map.class);
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        Map body = resp.getBody();
        assertNotNull(body);
        // 应包含 b 和 c
        java.util.List tables = (java.util.List) body.get("tables");
        assertNotNull(tables);
        assertTrue(tables.contains("b"));
        assertTrue(tables.contains("c"));
    }

    @Test
    @DisplayName("GET /api/v1/health 健康检查")
    void testHealthEndpoint() {
        ResponseEntity<Map> resp = restClient.get()
                .uri("http://localhost:" + port + "/api/v1/health")
                .retrieve()
                .toEntity(Map.class);
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        Map body = resp.getBody();
        assertNotNull(body);
        assertEquals("UP", body.get("status"));
    }

    @Test
    @DisplayName("POST /events 摄取 OpenLineage RunEvent 并可被 upstream 查询命中")
    void testOpenLineageEventsEndpoint() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("namespace", "batch-pipeline");
        input.put("name", "it-batch/01_raw");
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("namespace", "batch-pipeline");
        output.put("name", "it-batch/02_valid");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", "COMPLETE");
        event.put("job", Map.of("namespace", "batch-pipeline", "name", "batch-pipeline.validate"));
        event.put("run", Map.of("runId", "88888888-8888-8888-8888-888888888888"));
        event.put("inputs", java.util.List.of(input));
        event.put("outputs", java.util.List.of(output));

        ResponseEntity<Map> resp = restClient.post()
                .uri(baseUrl + "/events")
                .contentType(MediaType.APPLICATION_JSON)
                .body(event)
                .retrieve()
                .toEntity(Map.class);
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        Map body = resp.getBody();
        assertNotNull(body);
        assertEquals(1, body.get("edges"));
        assertEquals(2, body.get("nodes"));

        // 摄取结果进入内存图，与 SQL 血缘共用查询链路（upstream 查询见 OpenLineageIngestServiceTest）
        assertTrue(graphWriter.getDirectUpstream("batch-pipeline/it-batch/02_valid")
                .contains("batch-pipeline/it-batch/01_raw"));
    }

    @Test
    @DisplayName("POST /events 缺 job.name 返回 400")
    void testOpenLineageEventsInvalid() {
        final int[] capturedStatus = {0};
        restClient.post()
                .uri(baseUrl + "/events")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run", Map.of("runId", "r")))
                .retrieve()
                .onStatus(status -> status.is4xxClientError(),
                        (req, resp) -> capturedStatus[0] = resp.getStatusCode().value())
                .toEntity(Map.class);
        assertEquals(400, capturedStatus[0]);
    }
}