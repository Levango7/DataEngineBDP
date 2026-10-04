package com.levango7.dataenginebdp.governance.realtime.lineage;

import com.levango7.dataenginebdp.governance.realtime.model.FieldLineage;
import com.levango7.dataenginebdp.common.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实时血缘解析器。
 *
 * <p>整合 {@link FlinkCdcSqlLineageParser}（SQL 解析），解析结果经 {@link LineageIngestClient}
 * （图存储），提供端到端的实时血缘更新能力。
 *
 * <p>触发方式：
 * <ul>
 *   <li>主动触发：Flink CDC 作业提交时，调用 {@link #parseAndUpdate} 解析 SQL 并更新血缘图</li>
 *   <li>被动触发：元数据采集完成后，由 {@code GovernancePipelineOrchestrator} 调用，
 *       重新解析关联作业的 SQL，更新血缘</li>
 * </ul>
 *
 * <p>性能目标：血缘解析 + 图更新 ≤ 3s（治理闭环 10s 预算的一部分）。
 */
@Component
public class RealTimeLineageAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(RealTimeLineageAnalyzer.class);

    private final FlinkCdcSqlLineageParser sqlParser;
    /** 血缘外发通道（唯一写入者在 lineage-analyzer）；测试构造可为 null。 */
    private final LineageIngestClient ingestClient;
    private final Timer lineageTimer;

    /** 作业 SQL 缓存：jobId → sqlText（用于元数据变更后重新解析） */
    private final ConcurrentHashMap<String, String> jobSqlCache = new ConcurrentHashMap<>();

    /** 解析统计 */
    private final AtomicLong parseCount = new AtomicLong(0);
    private final AtomicLong updateSuccessCount = new AtomicLong(0);
    private final AtomicLong updateFailureCount = new AtomicLong(0);

    @Autowired
    public RealTimeLineageAnalyzer(FlinkCdcSqlLineageParser sqlParser,
                                   LineageIngestClient ingestClient,
                                   MeterRegistry meterRegistry) {
        this.sqlParser = sqlParser;
        this.ingestClient = ingestClient;
        this.lineageTimer = Timer.builder("governance.lineage.update.duration")
                .description("血缘解析与更新耗时")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    /** 测试用构造函数（无 MeterRegistry） */
    public RealTimeLineageAnalyzer(FlinkCdcSqlLineageParser sqlParser) {
        this.sqlParser = sqlParser;
        // 测试构造不带外发通道：emit 路径会走 null 分支并记日志，不影响既有断言
        this.ingestClient = null;
        this.lineageTimer = null;
    }

    /**
     * 解析 Flink CDC SQL 并实时更新血缘图。
     *
     * @param sqlText Flink CDC SQL 文本
     * @param jobId Flink 作业 ID
     * @return 解析得到的字段级血缘；更新失败时仍返回血缘（已写入缓存）
     */
    public FieldLineage parseAndUpdate(String sqlText, String jobId) {
        long start = System.currentTimeMillis();
        String tenantId = TenantContext.getTenantId();
        log.info("Parsing and updating lineage: jobId={}, tenantId={}", jobId, tenantId);

        // 缓存作业 SQL（用于元数据变更后重新解析）
        jobSqlCache.put(jobId, sqlText);

        // Step 1: 解析 SQL 提取字段级血缘
        FieldLineage lineage = sqlParser.parse(sqlText, jobId);
        // 写入租户 ID（多租户隔离，R10 安全修复）
        lineage.setTenantId(tenantId);
        parseCount.incrementAndGet();

        // Step 2: 外发给 lineage-analyzer（裁决 Q2：唯一写入者），失败不阻塞主流程
        boolean success = ingestClient != null && ingestClient.emitRunEvent(lineage, jobId);
        if (success) {
            updateSuccessCount.incrementAndGet();
        } else {
            updateFailureCount.incrementAndGet();
        }

        long duration = System.currentTimeMillis() - start;
        log.info("Lineage updated: {} → {}, mappings={}, duration={}ms",
                lineage.getSourceTable(), lineage.getTargetTable(),
                lineage.getFieldMappings().size(), duration);

        if (lineageTimer != null) {
            lineageTimer.record(java.time.Duration.ofMillis(duration));
        }
        return lineage;
    }

    /**
     * 根据目标表查找关联的作业并重新解析血缘。
     *
     * <p>当元数据采集完成后，目标表的 schema 可能变更，需要重新解析关联作业的 SQL，
     * 更新血缘图以反映最新的字段映射关系。
     *
     * @param targetTable 目标表标识符
     * @return 更新后的血缘列表（可能有多个作业写入同一表）
     */
    public java.util.List<FieldLineage> refreshLineageForTable(String targetTable) {
        java.util.List<FieldLineage> updated = new java.util.ArrayList<>();
        for (var entry : jobSqlCache.entrySet()) {
            String jobId = entry.getKey();
            String sqlText = entry.getValue();
            FieldLineage lineage = sqlParser.parse(sqlText, jobId);
            if (targetTable.equals(lineage.getTargetTable())) {
                if (ingestClient != null) {
                    ingestClient.emitRunEvent(lineage, jobId);
                }
                updated.add(lineage);
            }
        }
        return updated;
    }

    /**
     * 获取解析统计。
     */
    public java.util.Map<String, Long> getParseStats() {
        java.util.Map<String, Long> stats = new java.util.HashMap<>();
        stats.put("parseCount", parseCount.get());
        stats.put("updateSuccessCount", updateSuccessCount.get());
        stats.put("updateFailureCount", updateFailureCount.get());
        return stats;
    }

    /**
     * 获取作业 SQL 缓存（用于测试断言）。
     */
    public java.util.Map<String, String> getJobSqlCache() {
        return java.util.Collections.unmodifiableMap(jobSqlCache);
    }
}