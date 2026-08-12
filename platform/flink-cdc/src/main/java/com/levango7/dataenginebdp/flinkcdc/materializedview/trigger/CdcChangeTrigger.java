package com.levango7.dataenginebdp.flinkcdc.materializedview.trigger;

import com.levango7.dataenginebdp.flinkcdc.materializedview.model.MaterializedViewDef;
import com.levango7.dataenginebdp.flinkcdc.materializedview.model.RefreshPolicy;
import com.levango7.dataenginebdp.flinkcdc.model.ChangeRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * CDC 变更触发器：监听源表 CDC 变更流，按配置的批量阈值与去抖窗口触发物化视图刷新。
 *
 * <p>核心机制：</p>
 * <ol>
 *   <li>接收 {@link ChangeRecord} 流，按源表名路由到对应的物化视图</li>
 *   <li>为每个物化视图维护变更计数器，达到 {@link RefreshPolicy#getBatchThreshold()} 时触发刷新</li>
 *   <li>去抖窗口：在窗口时间内多次达到阈值仅触发一次，避免高频变更导致刷新风暴</li>
 * </ol>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * CdcChangeTrigger trigger = new CdcChangeTrigger(viewDefs);
 * trigger.registerHandler(refreshEvent -> viewRefresher.refresh(refreshEvent));
 * trigger.start();
 * // 在 Flink CDC 流处理算子中调用 trigger.onChange(record)
 * }</pre>
 *
 * @author shuqing-bigdata
 */
public class CdcChangeTrigger implements RefreshTrigger {

    private static final Logger log = LoggerFactory.getLogger(CdcChangeTrigger.class);

    /** 注册的物化视图定义列表。 */
    private final List<MaterializedViewDef> viewDefs;

    /** 源表 → 物化视图名称的映射（一个源表可能对应多个物化视图）。 */
    private final Map<String, String> tableToView = new HashMap<>();

    /** 物化视图名称 → 刷新策略。 */
    private final Map<String, RefreshPolicy> viewPolicies = new HashMap<>();

    /** 物化视图名称 → 当前累计变更数。 */
    private final Map<String, AtomicInteger> changeCounters = new HashMap<>();

    /** 物化视图名称 → 上次触发时间戳（毫秒）。 */
    private final Map<String, Long> lastTriggerTime = new HashMap<>();

    /** 事件处理器。 */
    private volatile Consumer<RefreshEvent> handler;

    /** 运行状态标志。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 构造器。
     *
     * @param viewDefs 物化视图定义列表（仅 EVENT_TRIGGERED 模式的视图会被监听）
     */
    public CdcChangeTrigger(List<MaterializedViewDef> viewDefs) {
        Objects.requireNonNull(viewDefs, "物化视图定义列表不能为 null");
        this.viewDefs = viewDefs;
        initialize();
    }

    /**
     * 初始化源表 → 物化视图映射。
     */
    private void initialize() {
        for (MaterializedViewDef def : viewDefs) {
            if (!def.isEnabled()) {
                continue;
            }
            RefreshPolicy policy = def.getRefreshPolicy();
            if (policy == null || policy.getMode() != RefreshPolicy.Mode.EVENT_TRIGGERED) {
                continue;
            }
            viewPolicies.put(def.getName(), policy);
            changeCounters.put(def.getName(), new AtomicInteger(0));
            for (String table : def.getSourceTables()) {
                tableToView.put(table, def.getName());
            }
            log.info("CDC 触发器注册物化视图: {}，监听源表: {}", def.getName(), def.getSourceTables());
        }
    }

    @Override
    public void registerHandler(Consumer<RefreshEvent> handler) {
        this.handler = Objects.requireNonNull(handler, "事件处理器不能为 null");
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("CDC 变更触发器已启动，监听 {} 个物化视图", viewPolicies.size());
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("CDC 变更触发器已停止");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 处理一条 CDC 变更记录。
     *
     * <p>从变更记录的 source 元数据中提取表名，查找关联的物化视图，
     * 累计变更计数，达到阈值且通过去抖检查后触发刷新事件。</p>
     *
     * @param record CDC 变更记录
     */
    public void onChange(ChangeRecord record) {
        if (!running.get()) {
            return;
        }
        Objects.requireNonNull(record, "ChangeRecord 不能为 null");
        String tableName = extractTableName(record);
        if (tableName == null) {
            return;
        }
        String viewName = tableToView.get(tableName);
        if (viewName == null) {
            return;
        }
        AtomicInteger counter = changeCounters.get(viewName);
        int count = counter.incrementAndGet();
        RefreshPolicy policy = viewPolicies.get(viewName);

        if (shouldTrigger(viewName, count, policy)) {
            counter.set(0);
            lastTriggerTime.put(viewName, System.currentTimeMillis());
            fireRefresh(viewName, count);
        }
    }

    /**
     * 判断是否应触发刷新（达到阈值且通过去抖检查）。
     *
     * @param viewName 物化视图名称
     * @param count    当前累计变更数
     * @param policy   刷新策略
     * @return 若应触发返回 true
     */
    boolean shouldTrigger(String viewName, int count, RefreshPolicy policy) {
        if (count < policy.getBatchThreshold()) {
            return false;
        }
        long debounceMs = policy.getDebounceWindow().toMillis();
        if (debounceMs <= 0) {
            return true;
        }
        Long lastTime = lastTriggerTime.get(viewName);
        if (lastTime == null) {
            return true;
        }
        return System.currentTimeMillis() - lastTime >= debounceMs;
    }

    /**
     * 触发刷新事件。
     *
     * @param viewName 物化视图名称
     * @param changeCount 累计变更数
     */
    private void fireRefresh(String viewName, int changeCount) {
        if (handler == null) {
            log.warn("物化视图 {} 达到刷新阈值但未注册处理器", viewName);
            return;
        }
        RefreshEvent event = RefreshEvent.cdc(viewName, changeCount + " 条 CDC 变更");
        log.info("CDC 触发物化视图刷新: {}，累计变更 {} 条", viewName, changeCount);
        try {
            handler.accept(event);
        } catch (Exception e) {
            log.error("处理 CDC 刷新事件失败: view={}", viewName, e);
        }
    }

    /**
     * 从 CDC 变更记录中提取表名（db.table 格式）。
     *
     * @param record 变更记录
     * @return 表名（db.table）；若无法提取返回 null
     */
    static String extractTableName(ChangeRecord record) {
        Map<String, Object> source = record.getSource();
        if (source == null) {
            return null;
        }
        Object db = source.get("db");
        Object table = source.get("table");
        if (db == null || table == null) {
            return null;
        }
        return db + "." + table;
    }

    /**
     * 获取当前指定物化视图的累计变更数（供测试与监控使用）。
     *
     * @param viewName 物化视图名称
     * @return 累计变更数；若视图未注册返回 0
     */
    public int getChangeCount(String viewName) {
        AtomicInteger counter = changeCounters.get(viewName);
        return counter == null ? 0 : counter.get();
    }

    /**
     * 获取监听的源表 → 物化视图映射（只读，供测试使用）。
     *
     * @return 不可修改的映射
     */
    public Map<String, String> getTableToViewMapping() {
        return Map.copyOf(tableToView);
    }
}