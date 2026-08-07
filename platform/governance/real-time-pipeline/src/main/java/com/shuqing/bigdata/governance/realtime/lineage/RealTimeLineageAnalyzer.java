package com.shuqing.bigdata.governance.realtime.lineage;

import com.shuqing.bigdata.governance.realtime.model.FieldLineage;
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
 * <p>整合 {@link FlinkCdcSqlLineageParser}（SQL 解析）与 {@link NebulaLineageGraphClient}
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
    private final NebulaLineageGraphClient graphClient;
    private final Timer lineageTimer;

    /** 作业 SQL 缓存：jobId → sqlText（用于元数据变更后重新解析） */
    private final ConcurrentHashMap<String, String> jobSqlCache = new ConcurrentHashMap<>();

    /** 解析统计 */
    private final AtomicLong parseCount = new AtomicLong(0);
    private final AtomicLong updateSuccessCount = new AtomicLong(0);
    private final AtomicLong updateFailureCount = new AtomicLong(0);

    @Autowired
    public RealTimeLineageAnalyzer(FlinkCdcSqlLineageParser sqlParser,
                                   NebulaLineageGraphClient graphClient,
                                   MeterRegistry meterRegistry) {
        this.sqlParser = sqlParser;
        this.graphClient = graphClient;
        this.lineageTimer = Timer.builder("governance.lineage.update.duration")
                .description("血缘解析与更新耗时")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    /** 测试用构造函数（无 MeterRegistry） */
    public RealTimeLineageAnalyzer(FlinkCdcSqlLineageParser sqlParser,
                                   NebulaLineageGraphClient graphClient) {
        this.sqlParser = sqlParser;
        this.graphClient = graphClient;
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
        log.info("Parsing and updating lineage: jobId={}", jobId);

        // 缓存作业 SQL（用于元数据变更后重新解析）
        jobSqlCache.put(jobId, sqlText);

        // Step 1: 解析 SQL 提取字段级血缘
        FieldLineage lineage = sqlParser.parse(sqlText, jobId);
        parseCount.incrementAndGet();

        // Step 2: 写入 NebulaGraph 血缘图
        boolean success = graphClient.writeLineage(lineage);
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
                graphClient.writeLineage(lineage);
                updated.add(lineage);
            }
        }
        return updated;
    }

    /**
     * 获取血缘图客户端（用于查询血缘）。
     */
    public NebulaLineageGraphClient getGraphClient() {
        return graphClient;
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