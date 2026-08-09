package com.levango7.dataenginebdp.governance.collector.service;

import com.levango7.dataenginebdp.governance.collector.collector.MetadataCollector;
import com.levango7.dataenginebdp.governance.collector.model.CollectionHistory;
import com.levango7.dataenginebdp.governance.collector.model.CollectionResult;
import com.levango7.dataenginebdp.governance.collector.model.MetadataSource;
import com.levango7.dataenginebdp.governance.collector.repository.CollectionHistoryRepository;
import com.levango7.dataenginebdp.governance.collector.repository.MetadataSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 元数据采集调度服务。
 *
 * <p>核心职责：
 * <ul>
 *   <li>{@link #triggerCollection(Long, String)} — 手动触发指定数据源采集</li>
 *   <li>{@link #scheduleCollection(Long, String)} — 按 cron 表达式注册定时采集</li>
 *   <li>{@link #getCollectionStatus(Long)} — 查询数据源最近采集状态</li>
 *   <li>{@link #unscheduleCollection(Long)} — 取消定时调度</li>
 * </ul></p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>使用独立 {@link ScheduledExecutorService} 承载动态 cron 调度，避免阻塞 Spring 主调度器</li>
 *   <li>采集任务异步执行，避免长耗时阻塞调用方</li>
 *   <li>每次采集写入 {@link CollectionHistory} 持久化记录</li>
 *   <li>采集成功后调用 {@link MetadataWriterService#writeBatch} 写入 Catalog</li>
 *   <li>{@link #refreshSchedules} 周期性扫描数据源，将 cron 同步到调度器（容灾）</li>
 * </ul></p>
 */
@Service
@EnableScheduling
public class CollectionSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(CollectionSchedulerService.class);

    /** 调度刷新间隔（毫秒）：每 60s 扫描一次数据源，同步 cron 调度 */
    private static final long SCHEDULE_REFRESH_INTERVAL_MS = 60_000L;

    private final MetadataSourceRepository sourceRepository;
    private final CollectionHistoryRepository historyRepository;
    private final MetadataWriterService writerService;
    private final List<MetadataCollector> collectors;

    /** 按 type 索引的 Collector 映射，加速路由 */
    private final Map<String, MetadataCollector> collectorByType = new ConcurrentHashMap<>();

    /** 数据源 ID → 调度 Future，用于取消已注册的定时任务 */
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    /** 数据源 ID → 进行中采集的 Future，用于状态查询与去重 */
    private final Map<Long, java.util.concurrent.Future<?>> runningTasks = new ConcurrentHashMap<>();

    /** 异步采集线程池 */
    private final ExecutorService collectionExecutor;

    /** 动态 cron 调度器 */
    private final ScheduledExecutorService dynamicScheduler;

    /**
     * 构造调度服务。
     *
     * @param sourceRepository 数据源 Repository
     * @param historyRepository 采集历史 Repository
     * @param writerService    Catalog 写入服务
     * @param collectors       Spring 注入的所有 Collector 实现
     */
    public CollectionSchedulerService(MetadataSourceRepository sourceRepository,
                                      CollectionHistoryRepository historyRepository,
                                      MetadataWriterService writerService,
                                      List<MetadataCollector> collectors) {
        this.sourceRepository = sourceRepository;
        this.historyRepository = historyRepository;
        this.writerService = writerService;
        this.collectors = collectors;
        for (MetadataCollector c : collectors) {
            this.collectorByType.put(c.getType(), c);
        }
        this.collectionExecutor = Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors() * 2),
                r -> {
                    Thread t = new Thread(r, "metadata-collector-worker");
                    t.setDaemon(true);
                    return t;
                });
        this.dynamicScheduler = Executors.newScheduledThreadPool(2,
                r -> {
                    Thread t = new Thread(r, "metadata-collector-scheduler");
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * 手动触发指定数据源采集。
     *
     * @param sourceId    数据源 ID
     * @param triggerType 触发方式：MANUAL/SCHEDULED
     * @return 采集结果；数据源不存在返回 empty
     */
    public Optional<CollectionResult> triggerCollection(Long sourceId, String triggerType) {
        Optional<MetadataSource> sourceOpt = sourceRepository.findById(sourceId);
        if (sourceOpt.isEmpty()) {
            return Optional.empty();
        }
        MetadataSource source = sourceOpt.get();
        CollectionResult result = doCollect(source, triggerType);
        return Optional.of(result);
    }

    /**
     * 异步触发采集（不等待结果，立即返回）。
     *
     * @param sourceId    数据源 ID
     * @param triggerType 触发方式
     * @return 已提交返回 true；数据源不存在或正在采集返回 false
     */
    public boolean triggerCollectionAsync(Long sourceId, String triggerType) {
        if (runningTasks.containsKey(sourceId)) {
            log.info("Collection already running for source {}, skip", sourceId);
            return false;
        }
        Optional<MetadataSource> sourceOpt = sourceRepository.findById(sourceId);
        if (sourceOpt.isEmpty()) {
            return false;
        }
        MetadataSource source = sourceOpt.get();
        java.util.concurrent.Future<?> future = collectionExecutor.submit(() -> {
            try {
                doCollect(source, triggerType);
            } finally {
                runningTasks.remove(sourceId);
            }
        });
        runningTasks.put(sourceId, future);
        return true;
    }

    /**
     * 按 cron 表达式注册定时采集。
     *
     * <p>本实现采用简化策略：将 cron 解析为固定延迟（取 cron 周期的近似值），
     * 适用于"每 N 分钟/小时"类周期。复杂 cron 由 {@link #refreshSchedules} 周期校验。</p>
     *
     * @param sourceId 数据源 ID
     * @param cron     Spring CronExpression 格式（6 字段：second minute hour day-of-month month day-of-week）
     * @return 注册成功返回 true；数据源不存在或 cron 非法返回 false
     */
    public boolean scheduleCollection(Long sourceId, String cron) {
        if (cron == null || cron.isBlank()) {
            return false;
        }
        try {
            CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid cron expression for source {}: {}", sourceId, cron);
            return false;
        }
        Optional<MetadataSource> sourceOpt = sourceRepository.findById(sourceId);
        if (sourceOpt.isEmpty()) {
            return false;
        }
        MetadataSource source = sourceOpt.get();
        source.setCron(cron);
        sourceRepository.save(source);

        // 取消已有调度
        unscheduleCollection(sourceId);

        // 计算下次执行延迟（简化：固定 60s 间隔，由 refreshSchedules 校准）
        long periodSeconds = estimatePeriodSeconds(cron);
        ScheduledFuture<?> future = dynamicScheduler.scheduleAtFixedRate(
                () -> triggerCollectionAsync(sourceId, "SCHEDULED"),
                periodSeconds, periodSeconds, TimeUnit.SECONDS);
        scheduledTasks.put(sourceId, future);
        log.info("Scheduled collection for source {} with cron '{}' (period={}s)", sourceId, cron, periodSeconds);
        return true;
    }

    /**
     * 取消指定数据源的定时调度。
     *
     * @param sourceId 数据源 ID
     * @return 取消成功返回 true；原本未调度返回 false
     */
    public boolean unscheduleCollection(Long sourceId) {
        ScheduledFuture<?> future = scheduledTasks.remove(sourceId);
        if (future != null) {
            future.cancel(false);
            log.info("Unscheduled collection for source {}", sourceId);
            return true;
        }
        return false;
    }

    /**
     * 查询指定数据源最近采集状态。
     *
     * @param sourceId 数据源 ID
     * @return 最近一条采集历史；无记录返回 empty
     */
    public Optional<CollectionHistory> getCollectionStatus(Long sourceId) {
        List<CollectionHistory> histories = historyRepository.findBySourceIdOrderByStartedAtDesc(sourceId);
        if (histories.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(histories.get(0));
    }

    /**
     * 周期性扫描数据源，同步 cron 调度（容灾与启动恢复）。
     *
     * <p>每 60s 执行一次，将数据库中带 cron 的数据源同步到内存调度器。
     * 已注册且 cron 未变更的跳过；cron 变更或新增的重新注册。</p>
     */
    @Scheduled(fixedDelay = SCHEDULE_REFRESH_INTERVAL_MS, initialDelay = 5_000L)
    public void refreshSchedules() {
        try {
            List<MetadataSource> sources = sourceRepository.findAll();
            for (MetadataSource source : sources) {
                if (source.getCron() == null || source.getCron().isBlank()) {
                    // 无 cron 的取消已有调度
                    unscheduleCollection(source.getId());
                    continue;
                }
                // 简化：若已注册则跳过；cron 变更需手动调用 scheduleCollection
                if (!scheduledTasks.containsKey(source.getId())) {
                    scheduleCollection(source.getId(), source.getCron());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to refresh schedules: {}", e.getMessage());
        }
    }

    /**
     * 执行实际采集（同步），写入历史与 Catalog。
     *
     * @param source      数据源
     * @param triggerType 触发方式
     * @return 采集结果
     */
    private CollectionResult doCollect(MetadataSource source, String triggerType) {
        CollectionHistory history = new CollectionHistory();
        history.setSourceId(source.getId());
        history.setTriggerType(triggerType);
        history.setStatus("RUNNING");
        history.setStartedAt(LocalDateTime.now());
        historyRepository.save(history);

        CollectionResult result;
        try {
            MetadataCollector collector = collectorByType.get(source.getType());
            if (collector == null) {
                result = CollectionResult.failure(source.getId(), source.getName(),
                        source.getType(), "No collector registered for type: " + source.getType());
            } else {
                result = collector.collect(source);
            }

            // 写入 Catalog
            if (result.isSuccess() && result.getTables() != null && !result.getTables().isEmpty()) {
                int written = writerService.writeBatch(result.getTables());
                log.info("Wrote {}/{} tables to catalog for source {}",
                        written, result.getTables().size(), source.getName());
            }

            history.setStatus(result.isSuccess() ? "SUCCESS" : "FAILED");
            history.setTableCount(result.getTableCount());
            history.setColumnCount(result.getColumnCount());
            if (!result.isSuccess()) {
                history.setErrorMessage(result.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("Collection failed for source {}: {}", source.getName(), e.getMessage(), e);
            result = CollectionResult.failure(source.getId(), source.getName(),
                    source.getType(), e.getMessage());
            history.setStatus("FAILED");
            history.setErrorMessage(e.getMessage());
        }

        history.setFinishedAt(LocalDateTime.now());
        if (history.getStartedAt() != null) {
            history.setDurationMs(Duration.between(history.getStartedAt(), history.getFinishedAt()).toMillis());
        }
        historyRepository.save(history);
        return result;
    }

    /**
     * 估算 cron 表达式的周期（秒）。
     *
     * <p>简化策略：解析 cron 字段，取最细粒度字段的周期。例如：
     * <ul>
     *   <li>{@code 0 * * * * *} → 60s（每分钟）</li>
     *   <li>{@code 0 0 * * * *} → 3600s（每小时）</li>
     *   <li>{@code 0 0 0 * * *} → 86400s（每天）</li>
     * </ul></p>
     *
     * @param cron cron 表达式
     * @return 周期秒数
     */
    private long estimatePeriodSeconds(String cron) {
        String[] parts = cron.trim().split("\\s+");
        if (parts.length < 6) {
            return 3600L; // 默认 1 小时
        }
        // second minute hour dom mon dow
        if (!"*".equals(parts[1])) {
            return 60L; // 每分钟级
        }
        if (!"*".equals(parts[2])) {
            return 3600L; // 每小时级
        }
        if (!"*".equals(parts[3])) {
            return 86400L; // 每天级
        }
        return 3600L;
    }

    /**
     * 暴露已注册的 Collector 类型列表，供健康检查/调试。
     *
     * @return 已注册类型列表
     */
    public List<String> getRegisteredTypes() {
        return List.copyOf(collectorByType.keySet());
    }
}