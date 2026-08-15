package com.levango7.dataenginebdp.sqlgateway.metering;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 查询计量收集器（稳妥模式）：
 * <ul>
 *   <li>有界缓冲（容量不足时丢弃新计量并记日志，不阻塞查询主链路）</li>
 *   <li>后台定时批量上报（RestTemplate POST）</li>
 *   <li>指数退避 + 熔断降级（连续失败暂停 N 秒，恢复后重试）</li>
 *   <li>上报失败记录本地警告，绝不影响查询响应</li>
 * </ul>
 */
@Slf4j
@Component
public class MeteringCollector {

    private static final int MAX_BUFFER = 1024;
    private static final long MAX_BACKOFF_MS = 60_000L;
    private static final int BATCH_SIZE = 64;

    private final BlockingQueue<QueryMeter> buffer = new LinkedBlockingQueue<>(MAX_BUFFER);
    // 必须设置超时：cost-model 不可达时无超时会阻塞上报线程
    private final RestTemplate restTemplate = buildRestTemplate();

    /** 上报目标（cost-model）。 */
    private final String meteringEndpoint;

    /** 连续失败计数。 */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    /** 熔断到期时间戳（毫秒）。 */
    private volatile long circuitOpenUntilMs = 0L;

    public MeteringCollector(@Value("${shuqing.finops.metering.endpoint:http://localhost:18090}") String endpoint) {
        this.meteringEndpoint = endpoint + "/api/v1/finops/metering/query";
    }

    /**
     * 接收一条计量（非阻塞；缓冲满时丢弃并告警）。
     */
    public void submit(QueryMeter meter) {
        if (meter == null) {
            return;
        }
        boolean offered = buffer.offer(meter);
        if (!offered) {
            log.warn("计量缓冲已满({}), 丢弃计量: tenant={}, requestId={}（不影响查询）",
                    MAX_BUFFER, meter.tenantId(), meter.clientRequestId());
        }
    }

    /**
     * 定时批量上报（每 15 秒）。
     */
    @Scheduled(fixedDelayString = "${shuqing.finops.metering.flush-interval-ms:15000}")
    public void flushScheduled() {
        if (buffer.isEmpty()) {
            return;
        }
        if (isCircuitOpen()) {
            log.warn("计量上报熔断中，跳过本次 flush");
            return;
        }
        List<QueryMeter> batch = new ArrayList<>(BATCH_SIZE);
        buffer.drainTo(batch, BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }
        doBatchUpload(batch);
    }

    /**
     * 上报成功回调（供外部链路使用；当前内部走 flush）。
     */
    @Async
    public CompletableFuture<Void> submitAsync(QueryMeter meter) {
        submit(meter);
        return CompletableFuture.completedFuture(null);
    }

    private void doBatchUpload(List<QueryMeter> batch) {
        try {
            List<java.util.Map<String, Object>> payloads = batch.stream()
                    .map(QueryMeter::toPayload)
                    .toList();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<List<java.util.Map<String, Object>>> entity = new HttpEntity<>(payloads, headers);
            restTemplate.postForEntity(meteringEndpoint, entity, String.class);
            int failures = consecutiveFailures.getAndSet(0);
            if (failures > 0) {
                log.info("计量上报已恢复，此前连续失败 {} 次", failures);
            }
            log.debug("计量批量上报成功: {} 条", batch.size());
        } catch (Exception e) {
            int failures = consecutiveFailures.incrementAndGet();
            long backoff = Math.min(MAX_BACKOFF_MS, (long) Math.pow(2, Math.min(failures, 6)) * 1_000L);
            circuitOpenUntilMs = System.currentTimeMillis() + backoff;
            // 重新入队（若缓冲仍满则丢弃，避免阻塞）
            batch.forEach(this::submit);
            log.warn("计量批次上报失败({}/{}): {},  退避 {}ms 后重试", failures, batch.size(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), backoff);
        }
    }

    private boolean isCircuitOpen() {
        return System.currentTimeMillis() < circuitOpenUntilMs;
    }

    /**
     * 当前缓冲积压量（监控用）。
     */
    public int pendingCount() {
        return buffer.size();
    }

    @PreDestroy
    public void shutdown() {
        // 停止前把剩余计量尽力上报一次（失败也仅记录，不抛出）
        List<QueryMeter> rest = new ArrayList<>();
        buffer.drainTo(rest, MAX_BUFFER);
        if (!rest.isEmpty()) {
            doBatchUpload(rest);
        }
        log.info("计量收集器已关闭, 残留 {} 条已尽力上报", rest.size());
    }

    /** RestTemplate 设置超时（无超时会阻塞上报线程）。 */
    private RestTemplate buildRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        return new RestTemplate(factory);
    }
}