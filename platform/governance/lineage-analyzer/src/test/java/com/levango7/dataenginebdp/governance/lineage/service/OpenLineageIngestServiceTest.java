package com.levango7.dataenginebdp.governance.lineage.service;

import com.levango7.dataenginebdp.governance.lineage.model.LineageNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OpenLineageIngestService} 单元测试。
 *
 * <p>事件样例对齐 platform/batch-pipeline {@code batch_pipeline.openlineage}
 * 发射器的事件结构（job/run/eventType/inputs/outputs）。</p>
 *
 * @author shuqing-bigdata
 */
@SpringBootTest
@Transactional
@DisplayName("OpenLineage RunEvent 摄取测试")
class OpenLineageIngestServiceTest {

    @Autowired
    private OpenLineageIngestService ingestService;

    @Autowired
    private LineageGraphWriter graphWriter;

    @BeforeEach
    void setUp() {
        graphWriter.clear();
    }

    private static Map<String, Object> dataset(String namespace, String name) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        if (namespace != null) {
            m.put("namespace", namespace);
        }
        m.put("name", name);
        return m;
    }

    private static Map<String, Object> event(String jobName, String runId,
                                             List<Map<String, Object>> inputs,
                                             List<Map<String, Object>> outputs) {
        Map<String, Object> ev = new java.util.LinkedHashMap<>();
        ev.put("eventType", "COMPLETE");
        ev.put("job", Map.of("namespace", "batch-pipeline", "name", jobName));
        ev.put("run", Map.of("runId", runId));
        ev.put("inputs", inputs);
        ev.put("outputs", outputs);
        return ev;
    }

    @Test
    @DisplayName("单事件 1 入 1 出 → 1 条表级边，可被上游查询命中")
    void singleEventMapsInputOutputEdge() {
        Map<String, Object> result = ingestService.ingest(event(
                "batch-pipeline.validate", "11111111-1111-1111-1111-111111111111",
                List.of(dataset("batch-pipeline", "b-001/01_raw")),
                List.of(dataset("batch-pipeline", "b-001/02_valid"))));

        assertEquals(1, result.get("events"));
        assertEquals(2, result.get("nodes"));
        assertEquals(1, result.get("edges"));
        assertTrue(graphWriter.getDirectUpstream("batch-pipeline/b-001/02_valid")
                .contains("batch-pipeline/b-001/01_raw"));
        assertTrue(graphWriter.getKnownTables().contains("batch-pipeline/b-001/01_raw"));
    }

    @Test
    @DisplayName("2 入 1 出 → 2 条边（笛卡尔积）")
    void cartesianProductOfInputsAndOutputs() {
        Map<String, Object> result = ingestService.ingest(event(
                "batch-pipeline.output", "22222222-2222-2222-2222-222222222222",
                List.of(dataset("batch-pipeline", "b-001/03_clean"),
                        dataset("batch-pipeline", "b-001/04_aggregates")),
                List.of(dataset("batch-pipeline", "b-001/05_output"))));

        assertEquals(2, result.get("edges"));
        assertEquals(3, result.get("nodes"));
    }

    @Test
    @DisplayName("pipeline 父事件（无 inputs/outputs）合法，0 边 0 节点")
    void parentEventWithoutIoIsAccepted() {
        Map<String, Object> ev = event("batch-pipeline.pipeline",
                "33333333-3333-3333-3333-333333333333", List.of(), List.of());
        ev.put("eventType", "START");

        Map<String, Object> result = ingestService.ingest(ev);

        assertEquals(0, result.get("edges"));
        assertEquals(0, result.get("nodes"));
        assertEquals("START", ((Map<?, ?>) ((List<?>) result.get("runs")).get(0)).get("eventType"));
    }

    @Test
    @DisplayName("事件数组聚合摄取（节点跨事件去重）")
    void arrayOfEventsIsAggregated() {
        Map<String, Object> result = ingestService.ingest(List.of(
                event("j1", "44444444-4444-4444-4444-444444444441",
                        List.of(dataset(null, "a")), List.of(dataset(null, "b"))),
                event("j2", "44444444-4444-4444-4444-444444444442",
                        List.of(dataset(null, "b")), List.of(dataset(null, "c")))));

        assertEquals(2, result.get("events"));
        assertEquals(2, result.get("edges"));
        // 事件间共享数据集 b，去重后为 a/b/c 三个节点
        assertEquals(3, result.get("nodes"));
        // 缺 namespace 的数据集回退 job.namespace
        assertTrue(graphWriter.getKnownTables().contains("batch-pipeline/a"));
    }

    @Test
    @DisplayName("重复事件幂等：边不重复写（Writer 查重）")
    void repeatedEventIsIdempotent() {
        Map<String, Object> ev = event("batch-pipeline.compute",
                "55555555-5555-5555-5555-555555555555",
                List.of(dataset("batch-pipeline", "b-001/03_clean")),
                List.of(dataset("batch-pipeline", "b-001/04_aggregates")));
        ingestService.ingest(ev);
        ingestService.ingest(ev);

        var edges = graphWriter.getDirectUpstream("batch-pipeline/b-001/04_aggregates");
        assertEquals(1, edges.size());
    }

    @Test
    @DisplayName("缺少 job.name / run.runId → 400 语义异常")
    void missingRequiredFieldsRejected() {
        IllegalArgumentException noJob = assertThrows(IllegalArgumentException.class,
                () -> ingestService.ingest(Map.of("run", Map.of("runId", "r"))));
        assertTrue(noJob.getMessage().contains("job.name"));

        IllegalArgumentException noRun = assertThrows(IllegalArgumentException.class,
                () -> ingestService.ingest(Map.of("job", Map.of("namespace", "n", "name", "j"))));
        assertTrue(noRun.getMessage().contains("run.runId"));
    }

    @Test
    @DisplayName("空请求体 / 非对象数组 → 拒绝")
    void emptyOrInvalidBodyRejected() {
        assertThrows(IllegalArgumentException.class, () -> ingestService.ingest(null));
        assertThrows(IllegalArgumentException.class, () -> ingestService.ingest(List.of()));
        assertThrows(IllegalArgumentException.class, () -> ingestService.ingest("str"));
    }

    @Test
    @DisplayName("数据集 namespace 逐条生效（缺省回退 job.namespace）")
    void datasetNamespaceFallbackAndEdgeDialect() {
        Map<String, Object> ev = event("j", "66666666-6666-6666-6666-666666666666",
                List.of(dataset(null, "x")),
                List.of(dataset("other-ns", "y")));

        ingestService.ingest(ev);

        assertTrue(graphWriter.getDirectUpstream("other-ns/y").contains("batch-pipeline/x"));
    }

    @Test
    @DisplayName("摄取的节点为 TABLE 类型且注册进内存图")
    void nodesAreTableType() {
        ingestService.ingest(event("j", "77777777-7777-7777-7777-777777777777",
                List.of(dataset("batch-pipeline", "b-001/01_raw")),
                List.of(dataset("batch-pipeline", "b-001/02_valid"))));

        // 内存图仅收录表级边端点，两节点即视为 TABLE 级注册成功
        assertEquals(2, graphWriter.getKnownTables().size());
        assertNotNull(graphWriter.getDirectDownstream("batch-pipeline/b-001/01_raw"));
        assertEquals(LineageNode.NodeType.TABLE, LineageNode.NodeType.valueOf("TABLE"));
    }
}
