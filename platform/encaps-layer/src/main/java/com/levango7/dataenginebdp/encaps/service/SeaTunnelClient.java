package com.levango7.dataenginebdp.encaps.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SeaTunnel REST API 客户端：启动/停止集成任务。
 *
 * <p>SeaTunnel 2.3.x REST API 端点：
 * <ul>
 *   <li>POST {@code /submit-job} — 提交作业（jobId 由 SeaTunnel 返回）</li>
 *   <li>POST {@code /stop-job/{jobId}} — 停止作业</li>
 *   <li>GET  {@code /running-jobs} — 列出运行中作业</li>
 * </ul>
 * 基地址由配置 {@code app.integrate.seatunnel-base-url} 注入。</p>
 */
@Slf4j
@Service
public class SeaTunnelClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public SeaTunnelClient(@Value("${app.integrate.seatunnel-base-url:http://localhost:5801}") String baseUrl) {
        this.restTemplate = new RestTemplate();
        this.baseUrl = baseUrl;
        log.info("SeaTunnel 客户端已初始化，base-url={}", baseUrl);
    }

    /**
     * 启动集成任务。
     *
     * @param taskId      平台任务 ID（用于日志关联）
     * @param sourceType  源类型（如 MySQL）
     * @param targetType  目标类型（如 Iceberg）
     * @param sourceTable 源表
     * @param targetTable 目标表
     * @return SeaTunnel 作业 ID（失败时返回 null）
     */
    public String startJob(Long taskId, String sourceType, String targetType,
                           String sourceTable, String targetTable) {
        String url = baseUrl + "/submit-job";
        Map<String, Object> request = new LinkedHashMap<>();
        // SeaTunnel 2.3.x 提交参数（简化版，真实环境按需扩展）
        request.put("jobName", "sync-" + taskId);
        request.put("sourcePlugin", sourceType);
        request.put("sinkPlugin", targetType);
        request.put("sourceTable", sourceTable);
        request.put("targetTable", targetTable);

        log.info("启动 SeaTunnel 任务: taskId={}, url={}, {}→{}", taskId, url, sourceType, targetType);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<Map> resp = restTemplate.postForEntity(url, entity, Map.class);
            if (resp.getBody() != null) {
                Object jobId = resp.getBody().get("jobId");
                if (jobId == null) {
                    jobId = resp.getBody().get("jobId");
                }
                return jobId == null ? null : String.valueOf(jobId);
            }
            return null;
        } catch (RestClientException e) {
            log.error("启动 SeaTunnel 任务失败: taskId={}, url={}", taskId, url, e);
            return null;
        }
    }

    /**
     * 停止集成任务。
     *
     * @param seatunnelJobId SeaTunnel 作业 ID
     * @return true 若已发送停止请求
     */
    public boolean stopJob(String seatunnelJobId) {
        if (seatunnelJobId == null || seatunnelJobId.isBlank()) {
            log.warn("停止 SeaTunnel 任务被拒绝：jobId 为空");
            return false;
        }
        String url = baseUrl + "/stop-job/" + seatunnelJobId;
        log.info("停止 SeaTunnel 任务: jobId={}, url={}", seatunnelJobId, url);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (RestClientException e) {
            log.error("停止 SeaTunnel 任务失败: jobId={}, url={}", seatunnelJobId, url, e);
            return false;
        }
    }

    /**
     * 检查 SeaTunnel 服务可达性。
     */
    public boolean isReachable() {
        try {
            restTemplate.getForObject(baseUrl + "/running-jobs", String.class);
            return true;
        } catch (RestClientException e) {
            return false;
        }
    }
}