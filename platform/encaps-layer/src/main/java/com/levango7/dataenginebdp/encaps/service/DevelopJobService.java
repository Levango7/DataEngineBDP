package com.levango7.dataenginebdp.encaps.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据开发作业服务：将 Web IDE 提交的作业转发到 stream-batch-scheduler。
 *
 * <p>调用链：前端 Web IDE → {@code DevelopController#runJob} →
 * {@link DevelopJobService#submitJob} → stream-batch-scheduler
 * {@code POST /api/v1/jobs}。</p>
 *
 * <p>stream-batch-scheduler 地址由配置 {@code app.develop.scheduler-base-url} 注入，
 * 默认 {@code http://localhost:18086}（与 application.yml 端口一致）。</p>
 */
@Slf4j
@Service
public class DevelopJobService {

    private final RestTemplate restTemplate;
    private final String schedulerBaseUrl;

    public DevelopJobService(
            @Value("${app.develop.scheduler-base-url:http://localhost:18086}") String schedulerBaseUrl) {
        this.restTemplate = new RestTemplate();
        this.schedulerBaseUrl = schedulerBaseUrl;
        log.info("数据开发作业服务已初始化，scheduler-base-url={}", schedulerBaseUrl);
    }

    /**
     * 提交作业到 stream-batch-scheduler。
     *
     * @param filePath    作业文件路径（工作空间相对路径）
     * @param engine      执行引擎（spark/flink/trino/doris）
     * @param cpu         CPU 核数（可空）
     * @param memory      内存 GB（可空）
     * @param parallelism 并发度（可空）
     * @param tenantId    租户 ID（用作 workspaceId）
     * @return 提交结果（含 runId、status、logs）
     */
    public Map<String, Object> submitJob(String filePath, String engine,
                                         Integer cpu, Integer memory, Integer parallelism,
                                         String tenantId) {
        String url = schedulerBaseUrl + "/api/v1/jobs";

        // 组装 stream-batch-scheduler JobController.JobRequest
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", deriveJobName(filePath));
        request.put("workspaceId", tenantId == null ? "default" : tenantId);
        request.put("type", mapEngineToJobType(engine));
        request.put("config", buildConfigJson(filePath, engine, cpu, memory, parallelism));
        request.put("owner", tenantId);

        log.info("提交作业到 stream-batch-scheduler: url={}, file={}, engine={}", url, filePath, engine);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<Map> resp = restTemplate.postForEntity(url, entity, Map.class);
            Map<String, Object> result = new LinkedHashMap<>();
            if (resp.getBody() != null && resp.getBody().get("id") != null) {
                result.put("runId", String.valueOf(resp.getBody().get("id")));
            } else {
                result.put("runId", "run-" + System.currentTimeMillis());
            }
            result.put("status", "running");
            result.put("logs", List.of(
                    Map.of("level", "info", "text", "已提交作业: " + filePath,
                            "timestamp", Instant.now().toString()),
                    Map.of("level", "ok", "text", "引擎: " + engine + "，已转交 stream-batch-scheduler",
                            "timestamp", Instant.now().toString())
            ));
            return result;
        } catch (RestClientException e) {
            log.error("提交作业到 stream-batch-scheduler 失败: url={}, file={}", url, filePath, e);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("runId", "run-" + System.currentTimeMillis());
            result.put("status", "failed");
            result.put("logs", List.of(
                    Map.of("level", "info", "text", "提交作业: " + filePath,
                            "timestamp", Instant.now().toString()),
                    Map.of("level", "error",
                            "text", "调度服务不可达: " + e.getMessage(),
                            "timestamp", Instant.now().toString())
            ));
            return result;
        }
    }

    /**
     * 从文件路径推导作业名（取文件名去除扩展）。
     */
    private String deriveJobName(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return "develop-job";
        }
        String name = filePath;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name;
    }

    /**
     * 引擎 → 作业类型（与 JobService.mapTaskType 对齐）。
     */
    private String mapEngineToJobType(String engine) {
        if (engine == null) {
            return "sql";
        }
        switch (engine.toLowerCase()) {
            case "spark":
                return "batch";
            case "flink":
                return "stream";
            case "trino":
            case "doris":
            case "sql":
                return "sql";
            default:
                return "sql";
        }
    }

    /**
     * 构造作业配置 JSON 字符串。
     */
    private String buildConfigJson(String filePath, String engine,
                                   Integer cpu, Integer memory, Integer parallelism) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("filePath", filePath);
        config.put("engine", engine);
        if (cpu != null) {
            config.put("cpu", cpu);
        }
        if (memory != null) {
            config.put("memory", memory);
        }
        if (parallelism != null) {
            config.put("parallelism", parallelism);
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(config);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("序列化作业配置失败，回退空配置", e);
            return "{}";
        }
    }
}