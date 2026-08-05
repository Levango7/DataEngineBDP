package com.shuqing.bigdata.governance.lineage.controller;

import com.shuqing.bigdata.governance.lineage.service.LineageAnalyzerService;
import com.shuqing.bigdata.governance.lineage.service.LineageGraphWriter;
import com.shuqing.bigdata.governance.lineage.service.LineageQueryService;
import com.shuqing.bigdata.sqlgateway.parser.SqlDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;

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

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private LineageGraphWriter graphWriter;

    @Autowired
    private LineageAnalyzerService analyzerService;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        graphWriter.clear();
        baseUrl = "http://localhost:" + port + "/lineage/api/v1/lineage";
    }

    @Test
    @DisplayName("POST /analyze 返回 ECharts 格式")
    void testAnalyzeEndpoint() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sql", "INSERT INTO b SELECT x FROM a");
        body.put("dialect", "ANSI");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> resp = restTemplate.postForEntity(
                baseUrl + "/analyze", entity, Map.class);
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

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> resp = restTemplate.postForEntity(
                baseUrl + "/analyze", entity, Map.class);
        assertTrue(resp.getStatusCode().is4xxClientError());
    }

    @Test
    @DisplayName("GET /upstream/{table} 返回上游")
    void testUpstreamEndpoint() {
        // 构造 a → b
        analyzerService.analyze("INSERT INTO b SELECT x FROM a", SqlDialect.ANSI);
        ResponseEntity<Map> resp = restTemplate.getForEntity(
                baseUrl + "/upstream/b", Map.class);
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        assertNotNull(resp.getBody());
    }

    @Test
    @DisplayName("GET /downstream/{table} 返回下游")
    void testDownstreamEndpoint() {
        analyzerService.analyze("INSERT INTO b SELECT x FROM a", SqlDialect.ANSI);
        ResponseEntity<Map> resp = restTemplate.getForEntity(
                baseUrl + "/downstream/a", Map.class);
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        assertNotNull(resp.getBody());
    }

    @Test
    @DisplayName("GET /impact/{table} 影响分析")
    void testImpactEndpoint() {
        analyzerService.analyze("INSERT INTO b SELECT x FROM a", SqlDialect.ANSI);
        analyzerService.analyze("INSERT INTO c SELECT x FROM b", SqlDialect.ANSI);
        ResponseEntity<Map> resp = restTemplate.getForEntity(
                baseUrl + "/impact/a", Map.class);
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
        ResponseEntity<Map> resp = restTemplate.getForEntity(
                "http://localhost:" + port + "/lineage/api/v1/health", Map.class);
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        Map body = resp.getBody();
        assertNotNull(body);
        assertEquals("UP", body.get("status"));
    }
}