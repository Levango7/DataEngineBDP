package com.levango7.dataenginebdp.rule.engine.orchestrator.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OrchestratorExtensionService} 单元测试。
 *
 * <p>覆盖所有 Service 方法，验证空 dagId 返回空列表、
 * 写入后能正确读取、intervene 更新介入状态、
 * checkpoint 生成唯一 ID、resume 返回正确状态。</p>
 */
class OrchestratorExtensionServiceTest {

    private OrchestratorExtensionService service;

    @BeforeEach
    void setUp() {
        service = new OrchestratorExtensionService();
    }

    /* ------------------------------ Agent 思考链 ------------------------------ */

    @Test
    void getThoughts_emptyDagId_shouldReturnEmptyList() {
        List<Map<String, Object>> thoughts = service.getThoughts("nonexistent");
        assertNotNull(thoughts);
        assertTrue(thoughts.isEmpty());
    }

    @Test
    void recordThought_thenGetThoughts_shouldReturnRecordedEntry() {
        Map<String, Object> thought = new LinkedHashMap<>();
        thought.put("step", "analyze");
        thought.put("content", "thinking about plan");

        Map<String, Object> recorded = service.recordThought("dag-1", thought);
        assertNotNull(recorded.get("id"));
        assertEquals("dag-1", recorded.get("dagId"));
        assertNotNull(recorded.get("timestamp"));

        List<Map<String, Object>> thoughts = service.getThoughts("dag-1");
        assertEquals(1, thoughts.size());
        assertEquals("analyze", thoughts.get(0).get("step"));
    }

    @Test
    void recordThought_multipleEntries_shouldReturnAll() {
        service.recordThought("dag-1", Map.of("step", "a"));
        service.recordThought("dag-1", Map.of("step", "b"));
        service.recordThought("dag-2", Map.of("step", "c"));

        assertEquals(2, service.getThoughts("dag-1").size());
        assertEquals(1, service.getThoughts("dag-2").size());
    }

    /* ------------------------------ 工具调用记录 ------------------------------ */

    @Test
    void getToolCalls_emptyDagId_shouldReturnEmptyList() {
        assertTrue(service.getToolCalls("nonexistent").isEmpty());
    }

    @Test
    void recordToolCall_thenGetToolCalls_shouldReturnRecordedEntry() {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("tool", "search");
        call.put("args", Map.of("q", "test"));

        Map<String, Object> recorded = service.recordToolCall("dag-1", call);
        assertNotNull(recorded.get("id"));
        assertEquals("dag-1", recorded.get("dagId"));

        List<Map<String, Object>> calls = service.getToolCalls("dag-1");
        assertEquals(1, calls.size());
        assertEquals("search", calls.get(0).get("tool"));
    }

    /* ------------------------------ 人工介入 ------------------------------ */

    @Test
    void getInterventions_emptyDagId_shouldReturnEmptyList() {
        assertTrue(service.getInterventions("nonexistent").isEmpty());
    }

    @Test
    void submitIntervention_newRequest_shouldCreateEntry() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decision", "APPROVED");
        payload.put("comment", "ok");

        Map<String, Object> result = service.submitIntervention("dag-1", payload);
        assertNotNull(result.get("id"));
        assertEquals("dag-1", result.get("dagId"));
        assertEquals("APPROVED", result.get("status"));

        List<Map<String, Object>> interventions = service.getInterventions("dag-1");
        assertEquals(1, interventions.size());
    }

    @Test
    void submitIntervention_withRequestId_shouldUpdateExistingEntry() {
        Map<String, Object> payload1 = new LinkedHashMap<>();
        payload1.put("requestId", "req-001");
        payload1.put("decision", "PENDING");
        service.submitIntervention("dag-1", payload1);

        Map<String, Object> payload2 = new LinkedHashMap<>();
        payload2.put("requestId", "req-001");
        payload2.put("decision", "APPROVED");
        Map<String, Object> updated = service.submitIntervention("dag-1", payload2);

        assertEquals("APPROVED", updated.get("status"));
        assertNotNull(updated.get("reviewedAt"));

        List<Map<String, Object>> interventions = service.getInterventions("dag-1");
        assertEquals(1, interventions.size());
        assertEquals("APPROVED", interventions.get(0).get("status"));
    }

    /* ------------------------------ 检查点 ------------------------------ */

    @Test
    void getCheckpoints_emptyDagId_shouldReturnEmptyList() {
        assertTrue(service.getCheckpoints("nonexistent").isEmpty());
    }

    @Test
    void createCheckpoint_shouldGenerateUniqueIdAndManualKind() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("note", "manual snapshot");

        Map<String, Object> cp1 = service.createCheckpoint("dag-1", body);
        Map<String, Object> cp2 = service.createCheckpoint("dag-1", body);

        assertNotNull(cp1.get("id"));
        assertNotNull(cp2.get("id"));
        assertNotEquals(cp1.get("id"), cp2.get("id"));
        assertTrue(((String) cp1.get("id")).startsWith("cp-"));
        assertEquals("MANUAL", cp1.get("kind"));
        assertEquals("dag-1", cp1.get("dagId"));
        assertEquals("manual snapshot", cp1.get("note"));

        assertEquals(2, service.getCheckpoints("dag-1").size());
    }

    @Test
    void createCheckpoint_nullBody_shouldNotThrow() {
        Map<String, Object> cp = service.createCheckpoint("dag-1", null);
        assertNotNull(cp.get("id"));
        assertEquals("MANUAL", cp.get("kind"));
    }

    /* ------------------------------ resume ------------------------------ */

    @Test
    void resumeFromCheckpoint_shouldReturnResumedTrueWithCheckpointId() {
        Map<String, Object> body = Map.of("checkpointId", "cp-abc");
        Map<String, Object> result = service.resumeFromCheckpoint("dag-1", body);

        assertEquals("dag-1", result.get("dagId"));
        assertEquals(true, result.get("resumed"));
        assertEquals("cp-abc", result.get("checkpointId"));
        assertNotNull(result.get("resumedAt"));
    }

    @Test
    void resumeFromCheckpoint_existingCheckpoint_shouldAttachSnapshot() {
        Map<String, Object> cp = service.createCheckpoint("dag-1", Map.of("note", "snap"));
        String cpId = (String) cp.get("id");

        Map<String, Object> result = service.resumeFromCheckpoint("dag-1",
                Map.of("checkpointId", cpId));
        assertNotNull(result.get("checkpoint"));
        assertEquals(cpId, ((Map<?, ?>) result.get("checkpoint")).get("id"));
    }

    @Test
    void resumeFromCheckpoint_nullBody_shouldReturnEmptyCheckpointId() {
        Map<String, Object> result = service.resumeFromCheckpoint("dag-1", null);
        assertEquals("", result.get("checkpointId"));
        assertEquals(true, result.get("resumed"));
    }

    /* ------------------------------ 执行历史 ------------------------------ */

    @Test
    void getExecutions_emptyDagId_shouldReturnEmptyList() {
        assertTrue(service.getExecutions("nonexistent").isEmpty());
    }

    @Test
    void recordExecution_thenGetExecutions_shouldReturnRecordedEntry() {
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("trigger", "MANUAL");
        exec.put("totalNodes", 5);

        Map<String, Object> recorded = service.recordExecution("dag-1", exec);
        assertNotNull(recorded.get("execId"));
        assertEquals("dag-1", recorded.get("dagId"));
        assertNotNull(recorded.get("startedAt"));

        List<Map<String, Object>> execs = service.getExecutions("dag-1");
        assertEquals(1, execs.size());
        assertEquals(5, execs.get(0).get("totalNodes"));
    }

    /* ------------------------------ 回放轨迹 ------------------------------ */

    @Test
    void getReplayTrace_notExist_shouldReturnPlaceholderWithEmptyEvents() {
        Map<String, Object> trace = service.getReplayTrace("dag-1", "exec-1");
        assertEquals("exec-1", trace.get("execId"));
        assertEquals("dag-1", trace.get("dagId"));
        assertNotNull(trace.get("events"));
        assertTrue(((List<?>) trace.get("events")).isEmpty());
    }

    @Test
    void recordReplayTrace_thenGetReplayTrace_shouldReturnRecordedEntry() {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("events", List.of(Map.of("kind", "START")));

        Map<String, Object> recorded = service.recordReplayTrace("dag-1", "exec-1", trace);
        assertEquals("exec-1", recorded.get("execId"));
        assertEquals("dag-1", recorded.get("dagId"));

        Map<String, Object> fetched = service.getReplayTrace("dag-1", "exec-1");
        assertEquals("exec-1", fetched.get("execId"));
        List<?> events = (List<?>) fetched.get("events");
        assertEquals(1, events.size());
    }

    /* ------------------------------ 清理 ------------------------------ */

    @Test
    void clearByDag_shouldRemoveAllRelatedRecords() {
        service.recordThought("dag-1", Map.of("step", "a"));
        service.recordToolCall("dag-1", Map.of("tool", "t"));
        service.submitIntervention("dag-1", Map.of("decision", "APPROVED"));
        service.createCheckpoint("dag-1", Map.of("note", "n"));
        service.recordExecution("dag-1", Map.of("trigger", "M"));
        service.recordReplayTrace("dag-1", "exec-1", Map.of("events", List.of()));

        service.clearByDag("dag-1");

        assertTrue(service.getThoughts("dag-1").isEmpty());
        assertTrue(service.getToolCalls("dag-1").isEmpty());
        assertTrue(service.getInterventions("dag-1").isEmpty());
        assertTrue(service.getCheckpoints("dag-1").isEmpty());
        assertTrue(service.getExecutions("dag-1").isEmpty());
        List<?> events = (List<?>) service.getReplayTrace("dag-1", "exec-1").get("events");
        assertTrue(events.isEmpty());
    }

    @Test
    void clearByDag_shouldNotAffectOtherDags() {
        service.recordThought("dag-1", Map.of("step", "a"));
        service.recordThought("dag-2", Map.of("step", "b"));

        service.clearByDag("dag-1");

        assertTrue(service.getThoughts("dag-1").isEmpty());
        assertFalse(service.getThoughts("dag-2").isEmpty());
    }
}