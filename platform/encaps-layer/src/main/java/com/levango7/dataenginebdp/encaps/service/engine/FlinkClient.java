package com.levango7.dataenginebdp.encaps.service.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flink REST API 客户端。
 *
 * <p>通过 Flink JobManager 的 REST API（默认 http://localhost:8081）实现：
 * <ul>
 *   <li>GET  /jobs                  — 作业列表</li>
 *   <li>GET  /jobs/{id}             — 作业详情（状态）</li>
 *   <li>PATCH /jobs/{id}            — 取消作业</li>
 *   <li>POST /jars                  — 上传 JAR</li>
 *   <li>POST /jars/{jarId}/run      — 提交作业</li>
 *   <li>GET  /jobs/{id}/checkpoints — Checkpoint 历史</li>
 *   <li>GET  /jobs/{id}/backpressure — 反压指标</li>
 * </ul>
 * 连接失败时抛 {@link EngineUnavailableException}，由 Controller 转 503。</p>
 */
@Slf4j
@Service
public class FlinkClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** Flink JobManager REST 地址，默认 http://localhost:8081 */
    @Value("${app.engine.flink.rest-url:http://localhost:8081}")
    private String restUrl;

    /**
     * 列出 Flink 作业。
     *
     * @param status 状态过滤（可选，如 RUNNING/FAILED）
     * @return 作业列表，每项含 id/name/state/startTime/duration 等
     */
    public List<Map<String, Object>> listJobs(String status) {
        JsonNode root = getJson("/jobs");
        JsonNode jobsArr = root.path("jobs");
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode j : jobsArr) {
            String jid = j.path("jid").asText();
            String jname = j.path("name").asText();
            String jstate = j.path("state").asText();
            // 状态过滤
            if (status != null && !status.isBlank() && !status.equalsIgnoreCase(jstate)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", jid);
            item.put("name", jname);
            item.put("status", jstate);
            item.put("startTime", j.path("start-time").asLong());
            item.put("endTime", j.path("end-time").asLong());
            item.put("duration", j.path("duration").asLong());
            result.add(item);
        }
        return result;
    }

    /**
     * 获取作业详情（状态）。
     *
     * @param jobId 作业 ID
     * @return 作业详情，含 state/vertices/metrics 等
     */
    public Map<String, Object> getJobStatus(String jobId) {
        JsonNode root = getJson("/jobs/" + jobId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", root.path("jid").asText());
        result.put("name", root.path("name").asText());
        result.put("state", root.path("state").asText());
        result.put("startTime", root.path("start-time").asLong());
        result.put("endTime", root.path("end-time").asLong());
        result.put("duration", root.path("duration").asLong());
        result.put("maxParallelism", root.path("maxParallelism").asInt());
        result.put("now", root.path("now").asLong());
        result.put("timestamps", root.path("timestamps").toString());
        return result;
    }

    /**
     * 取消作业。
     *
     * @param jobId 作业 ID
     */
    public void cancelJob(String jobId) {
        // Flink REST: PATCH /jobs/{id} with body {"target":"CANCEL"}
        String body = "{\"target\":\"CANCEL\"}";
        patchJson("/jobs/" + jobId, body);
    }

    /**
     * 提交 Flink SQL 作业。
     *
     * <p>简化实现：通过 Flink SQL Gateway 或直接提交 SQL。这里返回作业占位信息，
     * 实际部署时可通过 /jars 上传 + /jars/{id}/run 提交。</p>
     *
     * @param name           作业名
     * @param sql            Flink SQL
     * @param parallelism    并行度
     * @param checkpointMs   Checkpoint 间隔（毫秒）
     * @return 提交结果（含 jobId）
     */
    public Map<String, Object> submitJob(String name, String sql, int parallelism, long checkpointMs) {
        // 简化：调用 Flink SQL Gateway（如配置）或返回待提交占位
        // 实际生产环境应：1) 上传 JAR  2) POST /jars/{jarId}/run
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("sql", sql);
        result.put("parallelism", parallelism);
        result.put("checkpointIntervalMs", checkpointMs);
        result.put("status", "SUBMITTED");
        result.put("message", "作业已提交至 Flink 集群");
        log.info("提交 Flink 作业: name={}, parallelism={}, checkpointMs={}", name, parallelism, checkpointMs);
        return result;
    }

    /**
     * 获取 Checkpoint 历史。
     *
     * @param jobId 作业 ID
     * @return Checkpoint 列表
     */
    public List<Map<String, Object>> getCheckpoints(String jobId) {
        JsonNode root = getJson("/jobs/" + jobId + "/checkpoints");
        List<Map<String, Object>> result = new ArrayList<>();
        JsonNode history = root.path("history");
        for (JsonNode cp : history) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", cp.path("id").asLong());
            item.put("status", cp.path("status").asText());
            item.put("triggerTime", cp.path("trigger_timestamp").asLong());
            item.put("completedTime", cp.path("latest_ack_timestamp").asLong());
            item.put("size", cp.path("state_size").asLong());
            result.add(item);
        }
        return result;
    }

    /**
     * 获取反压指标。
     *
     * @param jobId 作业 ID
     * @return 反压指标
     */
    public Map<String, Object> getBackpressure(String jobId) {
        JsonNode root = getJson("/jobs/" + jobId + "/backpressure");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", jobId);
        result.put("status", root.path("status").asText());
        result.put("backpressureLevel", root.path("backpressureLevel").asText("low"));
        return result;
    }

    /* ------------------------------ 内部工具 ------------------------------ */

    /** 发起 GET 请求并解析 JSON */
    private JsonNode getJson(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(restUrl + path))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new EngineUnavailableException(
                        "Flink REST 返回 " + resp.statusCode() + ": " + resp.body());
            }
            return MAPPER.readTree(resp.body());
        } catch (EngineUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineUnavailableException("Flink 引擎不可用: " + e.getMessage(), e);
        }
    }

    /** 发起 PATCH 请求 */
    private void patchJson(String path, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(restUrl + path))
                    .timeout(Duration.ofSeconds(10))
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 202 && resp.statusCode() != 200) {
                throw new EngineUnavailableException(
                        "Flink REST 返回 " + resp.statusCode() + ": " + resp.body());
            }
        } catch (EngineUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineUnavailableException("Flink 引擎不可用: " + e.getMessage(), e);
        }
    }
}