package com.shuqing.bigdata.sqlgateway.virtual.materialize;

import com.shuqing.bigdata.sqlgateway.virtual.VirtualTableDefinition;
import com.shuqing.bigdata.sqlgateway.virtual.VirtualTableRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 物化表定时刷新调度器。
 *
 * <p>通过 Spring {@code @Scheduled} 定时扫描全部启用物化策略的虚拟表，
 * 对到达刷新间隔的表触发 {@link MaterializationService#refresh}。</p>
 *
 * <p>调度策略：</p>
 * <ul>
 *   <li>每 60 秒扫描一次；</li>
 *   <li>仅处理 {@code strategy=FULL/INCREMENTAL} 且 {@code enabled=true} 的虚拟表；</li>
 *   <li>距上次刷新时间超过 {@code refreshIntervalSeconds} 才触发；</li>
 *   <li>单线程串行刷新，避免对外部源造成并发压力。</li>
 * </ul>
 *
 * <p>需要在主类启用 {@code @EnableScheduling}。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class MaterializationScheduler {

    private static final Logger log = LoggerFactory.getLogger(MaterializationScheduler.class);

    private final VirtualTableRepository repository;
    private final MaterializationService materializationService;

    /**
     * 构造调度器。
     *
     * @param repository            虚拟表仓储
     * @param materializationService 物化执行服务
     */
    public MaterializationScheduler(VirtualTableRepository repository,
                                    MaterializationService materializationService) {
        this.repository = repository;
        this.materializationService = materializationService;
    }

    /**
     * 定时扫描并刷新物化表。
     *
     * <p>每 60 秒执行一次。扫描全部 FULL/INCREMENTAL 策略的虚拟表，
     * 对到达刷新间隔的表触发刷新。</p>
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void scheduledRefresh() {
        try {
            List<VirtualTableDefinition> fullTables = repository
                    .findByMaterializationStrategyAndEnabled("FULL", true);
            List<VirtualTableDefinition> incrementalTables = repository
                    .findByMaterializationStrategyAndEnabled("INCREMENTAL", true);

            int total = fullTables.size() + incrementalTables.size();
            if (total == 0) {
                return;
            }
            log.debug("物化调度扫描开始 total={}", total);

            int refreshed = 0;
            for (VirtualTableDefinition def : fullTables) {
                if (shouldRefresh(def)) {
                    refreshed += doRefresh(def);
                }
            }
            for (VirtualTableDefinition def : incrementalTables) {
                if (shouldRefresh(def)) {
                    refreshed += doRefresh(def);
                }
            }
            if (refreshed > 0) {
                log.info("物化调度完成 total={} refreshed={}", total, refreshed);
            }
        } catch (Exception e) {
            log.error("物化调度异常 err={}", e.getMessage(), e);
        }
    }

    /**
     * 判断虚拟表是否需要刷新。
     *
     * @param definition 虚拟表定义
     * @return {@code true} 表示需要刷新
     */
    private boolean shouldRefresh(VirtualTableDefinition definition) {
        Integer interval = definition.getRefreshIntervalSeconds();
        if (interval == null || interval <= 0) {
            return false;
        }
        Instant last = definition.getLastRefreshTime();
        if (last == null) {
            return true;
        }
        long elapsed = Instant.now().getEpochSecond() - last.getEpochSecond();
        return elapsed >= interval;
    }

    /**
     * 执行单表刷新，捕获异常避免中断调度。
     *
     * @param definition 虚拟表定义
     * @return 1 表示刷新成功，0 表示失败
     */
    private int doRefresh(VirtualTableDefinition definition) {
        try {
            materializationService.refresh(definition);
            return 1;
        } catch (Exception e) {
            log.error("物化刷新失败 table={} tenant={} err={}",
                    definition.getTableName(), definition.getTenantId(), e.getMessage());
            return 0;
        }
    }

    /**
     * 手动触发指定虚拟表的刷新。
     *
     * @param definition 虚拟表定义
     * @return 刷新行数
     */
    public int manualRefresh(VirtualTableDefinition definition) {
        log.info("手动刷新物化表 table={} tenant={}",
                definition.getTableName(), definition.getTenantId());
        return materializationService.refresh(definition);
    }
}