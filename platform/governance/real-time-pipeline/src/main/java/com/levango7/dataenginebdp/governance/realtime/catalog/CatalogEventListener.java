package com.levango7.dataenginebdp.governance.realtime.catalog;

import com.levango7.dataenginebdp.governance.realtime.model.CatalogCommitEvent;
import com.levango7.dataenginebdp.governance.realtime.model.TableMetadata;
import com.levango7.dataenginebdp.governance.realtime.pipeline.GovernancePipelineOrchestrator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Iceberg REST Catalog 事件监听器。
 *
 * <p>支持两种事件接收模式：
 * <ol>
 *   <li><b>Webhook 模式</b>（推荐）：Iceberg REST Catalog 配置 webhook，commit 后主动
 *       POST 事件到 {@code POST /api/v1/governance/catalog/events}。延迟最低（毫秒级）。</li>
 *   <li><b>轮询模式</b>（兜底）：定时轮询 Catalog snapshots API，对比已处理 snapshot-id
 *       发现新 commit。延迟取决于轮询间隔（默认 1s）。</li>
 * </ol>
 *
 * <p>事件处理流程（异步，不阻塞 Catalog commit）：
 * <ol>
 *   <li>接收 {@link CatalogCommitEvent}，记录接收时间戳</li>
 *   <li>异步触发 {@link MetadataCollector#collect} 采集元数据（≤ 5s）</li>
 *   <li>采集完成后，触发 {@link GovernancePipelineOrchestrator} 继续血缘更新 → 质量评估</li>
 * </ol>
 */
@Component
@RestController
public class CatalogEventListener {

    private static final Logger log = LoggerFactory.getLogger(CatalogEventListener.class);

    private final MetadataCollector metadataCollector;
    private final GovernancePipelineOrchestrator orchestrator;
    private final IcebergRestCatalogClient catalogClient;
    private final Counter eventReceivedCounter;
    private final Counter eventProcessedCounter;
    private final Counter eventFailedCounter;

    @Value("${governance.iceberg.poll-interval-ms:1000}")
    private long pollIntervalMs;

    @Value("${governance.iceberg.monitored-namespaces:default}")
    private String monitoredNamespaces;

    @Autowired
    public CatalogEventListener(MetadataCollector metadataCollector,
                                GovernancePipelineOrchestrator orchestrator,
                                IcebergRestCatalogClient catalogClient,
                                MeterRegistry meterRegistry) {
        this.metadataCollector = metadataCollector;
        this.orchestrator = orchestrator;
        this.catalogClient = catalogClient;
        this.eventReceivedCounter = Counter.builder("governance.catalog.events.received")
                .description("接收到的 Catalog commit 事件数")
                .register(meterRegistry);
        this.eventProcessedCounter = Counter.builder("governance.catalog.events.processed")
                .description("成功处理的 Catalog commit 事件数")
                .register(meterRegistry);
        this.eventFailedCounter = Counter.builder("governance.catalog.events.failed")
                .description("处理失败的 Catalog commit 事件数")
                .register(meterRegistry);
    }

    /** 测试用构造函数（无 MeterRegistry） */
    public CatalogEventListener(MetadataCollector metadataCollector,
                                GovernancePipelineOrchestrator orchestrator,
                                IcebergRestCatalogClient catalogClient) {
        this.metadataCollector = metadataCollector;
        this.orchestrator = orchestrator;
        this.catalogClient = catalogClient;
        this.eventReceivedCounter = null;
        this.eventProcessedCounter = null;
        this.eventFailedCounter = null;
    }

    // -----------------------------------------------------------------------
    // Webhook 模式：Iceberg REST Catalog 主动推送事件
    // -----------------------------------------------------------------------

    /**
     * Webhook 端点：接收 Iceberg REST Catalog 推送的 commit 事件。
     *
     * <p>Iceberg REST Catalog 配置：
     * <pre>
     * catalog.webhook.url=http://governance:18090/api/v1/governance/catalog/events
     * catalog.webhook.events=update-snapshot,append-snapshot,overwrite-snapshot,replace-snapshot
     * </pre>
     *
     * @param event commit 事件
     * @return 处理结果（ACK）
     */
    @PostMapping("/api/v1/governance/catalog/events")
    public String handleWebhookEvent(@RequestBody CatalogCommitEvent event) {
        if (event.getReceivedTimestamp() == null) {
            event.setReceivedTimestamp(Instant.now());
        }
        if (event.getEventId() == null) {
            event.setEventId(java.util.UUID.randomUUID().toString());
        }
        log.info("Received webhook event: table={}, eventType={}, eventId={}",
                event.getTableIdentifier(), event.getEventType(), event.getEventId());

        if (eventReceivedCounter != null) {
            eventReceivedCounter.increment();
        }

        // 标记已处理，避免轮询模式重复处理
        if (event.getNewSnapshotId() != null) {
            catalogClient.markProcessed(event.getTableIdentifier(), event.getNewSnapshotId());
        }

        // 异步处理事件
        processEventAsync(event);
        return "ACK";
    }

    // -----------------------------------------------------------------------
    // 轮询模式：定时轮询 Catalog snapshots API
    // -----------------------------------------------------------------------

    /**
     * 定时轮询 Catalog commit 事件。
     *
     * <p>对每个受监控命名空间下的每个表，调用 {@link IcebergRestCatalogClient#pollCommitEvents}
     * 发现新 commit，异步处理。
     *
     * <p>调度间隔由 {@code governance.iceberg.poll-interval-ms} 配置（默认 1s）。
     * Webhook 模式启用时，轮询作为兜底，间隔可适当放大。
     */
    @Scheduled(fixedDelayString = "${governance.iceberg.poll-interval-ms:1000}")
    public void pollCatalogEvents() {
        String[] namespaces = monitoredNamespaces.split(",");
        for (String namespace : namespaces) {
            namespace = namespace.trim();
            if (namespace.isEmpty()) {
                continue;
            }
            List<String> tables = catalogClient.listTables(namespace);
            for (String tableName : tables) {
                List<CatalogCommitEvent> events = catalogClient.pollCommitEvents(namespace, tableName);
                for (CatalogCommitEvent event : events) {
                    if (eventReceivedCounter != null) {
                        eventReceivedCounter.increment();
                    }
                    processEventAsync(event);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 事件处理（异步）
    // -----------------------------------------------------------------------

    /**
     * 异步处理 commit 事件：元数据采集 → 触发治理闭环。
     *
     * <p>使用 {@code @Async("governanceAsyncExecutor")} 在专用线程池执行，
     * 避免阻塞 Catalog commit 主线程或轮询线程。
     *
     * @param event commit 事件
     */
    @Async("governanceAsyncExecutor")
    @EventListener
    public void processEventAsync(CatalogCommitEvent event) {
        long start = System.currentTimeMillis();
        try {
            log.debug("Processing event: table={}, eventType={}",
                    event.getTableIdentifier(), event.getEventType());

            // Step 1: 元数据采集（≤ 5s）
            TableMetadata metadata = metadataCollector.collect(event);
            if (metadata == null) {
                log.warn("Metadata collection failed, skipping downstream: {}", event.getTableIdentifier());
                if (eventFailedCounter != null) {
                    eventFailedCounter.increment();
                }
                return;
            }

            // Step 2: 触发治理闭环（血缘更新 → 质量评估 → 告警）
            orchestrator.onMetadataCollected(event, metadata);

            long totalLatency = System.currentTimeMillis() - start;
            log.info("Event processed: table={}, totalLatency={}ms",
                    event.getTableIdentifier(), totalLatency);
            if (eventProcessedCounter != null) {
                eventProcessedCounter.increment();
            }
        } catch (Exception e) {
            log.error("Event processing failed: table={}, eventId={}: {}",
                    event.getTableIdentifier(), event.getEventId(), e.getMessage(), e);
            if (eventFailedCounter != null) {
                eventFailedCounter.increment();
            }
        }
    }
}