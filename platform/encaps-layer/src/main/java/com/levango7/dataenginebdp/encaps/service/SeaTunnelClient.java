package com.levango7.dataenginebdp.encaps.service;

import com.levango7.dataenginebdp.common.security.resilience.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
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
 *
 * <p>A4 韧性增强：
 * <ul>
 *   <li>连接/读超时 5s（此前 RestTemplate 默认无超时，下游挂起会拖死业务线程）</li>
 *   <li>熔断器：最近 10 次调用失败率 ≥50% 跳闸，冷却 30s 后半开试探——
 *       SeaTunnel 持续宕机时不再逐次等待超时，直接快速失败</li>
 * </ul></p>
 */
@Slf4j
@Service
public class SeaTunnelClient {

    /** 连接超时（秒）。 */
    private static final int CONNECT_TIMEOUT_SECONDS = 5;

    /** 读超时（秒）。 */
    private static final int READ_TIMEOUT_SECONDS = 5;

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final CircuitBreaker breaker;

    public SeaTunnelClient(@Value("${app.integrate.seatunnel-base-url:http://localhost:5801}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(READ_TIMEOUT_SECONDS).toMillis());
        this.restTemplate = new RestTemplate(factory);
        this.baseUrl = baseUrl;
        // 窗口 10 / 失败率 50% / 最小样本 5 / 冷却 30s
        this.breaker = new CircuitBreaker("seatunnel", 10, 0.5, 5,
                Duration.ofSeconds(30), CircuitBreaker.SYSTEM_NANOS);
        log.info("SeaTunnel 客户端已初始化，base-url={}, 超时={}s/{}s, 熔断=窗口10@50%/30s",
                baseUrl, CONNECT_TIMEOUT_SECONDS, READ_TIMEOUT_SECONDS);
    }

    /**
     * 启动集成任务。
     *
     * @param taskId      平台任务 ID（用于日志关联）
     * @param sourceType  源类型（如 MySQL）
     * @param targetType  目标类型（如 Iceberg）
     * @param sourceTable 源表
     * @param targetTable 目标表
     * @return SeaTunnel 作业 ID（失败或熔断时返回 null）
     */
    public String startJob(Long taskId, String sourceType, String targetType,
                           String sourceTable, String targetTable) {
        if (!breaker.tryAcquire()) {
            log.warn("SeaTunnel 熔断中（{}），跳过启动任务: taskId={}", breaker.getState(), taskId);
            return null;
        }
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
            breaker.recordSuccess();
            if (resp.getBody() != null) {
                Object jobId = resp.getBody().get("jobId");
                if (jobId == null) {
                    jobId = resp.getBody().get("jobId");
                }
                return jobId == null ? null : String.valueOf(jobId);
            }
            return null;
        } catch (RestClientException e) {
            breaker.recordFailure();
            log.error("启动 SeaTunnel 任务失败: taskId={}, url={}, breaker={}", taskId, url,
                    breaker.getState());
            return null;
        }
    }

    /**
     * 停止集成任务。
     *
     * @param seatunnelJobId SeaTunnel 作业 ID
     * @return true 若已发送停止请求（熔断中返回 false）
     */
    public boolean stopJob(String seatunnelJobId) {
        if (seatunnelJobId == null || seatunnelJobId.isBlank()) {
            log.warn("停止 SeaTunnel 任务被拒绝：jobId 为空");
            return false;
        }
        if (!breaker.tryAcquire()) {
            log.warn("SeaTunnel 熔断中（{}），跳过停止任务: jobId={}", breaker.getState(), seatunnelJobId);
            return false;
        }
        String url = baseUrl + "/stop-job/" + seatunnelJobId;
        log.info("停止 SeaTunnel 任务: jobId={}, url={}", seatunnelJobId, url);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            breaker.recordSuccess();
            return resp.getStatusCode().is2xxSuccessful();
        } catch (RestClientException e) {
            breaker.recordFailure();
            log.error("停止 SeaTunnel 任务失败: jobId={}, url={}, breaker={}", seatunnelJobId, url,
                    breaker.getState());
            return false;
        }
    }

    /**
     * 检查 SeaTunnel 服务可达性。
     *
     * <p>健康探测不走熔断守卫（探测的意义就是获知当前状态，包括熔断中的状态），
     * 但结果计入熔断窗口——连续探测失败会累积跳闸。</p>
     */
    public boolean isReachable() {
        try {
            restTemplate.getForObject(baseUrl + "/running-jobs", String.class);
            breaker.recordSuccess();
            return true;
        } catch (RestClientException e) {
            breaker.recordFailure();
            return false;
        }
    }
}