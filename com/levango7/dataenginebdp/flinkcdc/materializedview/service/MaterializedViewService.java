package com.shuqing.bigdata.flinkcdc.materializedview.service;

import com.shuqing.bigdata.flinkcdc.materializedview.config.MaterializedViewConfig;
import com.shuqing.bigdata.flinkcdc.materializedview.model.MaterializedViewDef;
import com.shuqing.bigdata.flinkcdc.materializedview.refresh.ViewRefresher;
import com.shuqing.bigdata.flinkcdc.materializedview.trigger.CdcChangeTrigger;
import com.shuqing.bigdata.flinkcdc.materializedview.trigger.ManualTrigger;
import com.shuqing.bigdata.flinkcdc.materializedview.trigger.RefreshEvent;
import com.shuqing.bigdata.flinkcdc.materializedview.trigger.RefreshTrigger;
import com.shuqing.bigdata.flinkcdc.materializedview.trigger.ScheduledTrigger;
import com.shuqing.bigdata.flinkcdc.model.ChangeRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 物化视图管理服务：统一管理物化视图定义的 CRUD、触发器编排与刷新执行。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>物化视图定义注册 / 查询 / 更新 / 删除</li>
 *   <li>编排三种触发器（CDC / 定时 / 手动）并统一分发到 {@link ViewRefresher}</li>
 *   <li>提供手动刷新入口（供 REST Controller 调用）</li>
 *   <li>维护刷新状态与结果查询</li>
 * </ul>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * MaterializedViewService service = new MaterializedViewService(config, sqlExecutor);
 * service.init();
 * service.registerView(viewDef);
 * service.start();
 * // CDC 流处理中
 * service.onCdcChange(record);
 * // 手动刷新
 * service.refreshManually("mv_name", "admin");
 * }</pre>
 *
 * @author shuqing-bigdata
 */
@Service
public class MaterializedViewService {

    private static final Logger log = LoggerFactory.getLogger(MaterializedViewService.class);

    /** 全局配置。 */
    private final MaterializedViewConfig config;

    /** SQL 执行器。 */
    private final Function<String, Boolean> sqlExecutor;

    /** 物化视图定义注册表：viewName → def。 */
    private final ConcurrentHashMap<String, MaterializedViewDef> viewRegistry = new ConcurrentHashMap<>();

    /** 视图刷新执行器。 */
    private ViewRefresher viewRefresher;

    /** CDC 变更触发器。 */
    private CdcChangeTrigger cdcTrigger;

    /** 定时触发器。 */
    private ScheduledTrigger scheduledTrigger;

    /** 手动触发器。 */
    private ManualTrigger manualTrigger;

    /** 是否已启动。 */
    private volatile boolean started = false;

    /**
     * 构造器。
     *
     * @param config      全局配置
     * @param sqlExecutor SQL 执行器
     */
    public MaterializedViewService(MaterializedViewConfig config,
                                   Function<String, Boolean> sqlExecutor) {
        this.config = Objects.requireNonNull(config, "配置不能为 null");
        this.sqlExecutor = Objects.requireNonNull(sqlExecutor, "SQL 执行器不能为 null");
    }

    /**
     * 初始化服务：创建刷新执行器与触发器（不启动）。
     */
    public void init() {
        Function<String, MaterializedViewDef> resolver = viewRegistry::get;
        this.viewRefresher = new ViewRefresher(resolver, config, sqlExecutor);

        List<MaterializedViewDef> defs = new ArrayList<>(viewRegistry.values());
        this.cdcTrigger = new CdcChangeTrigger(defs);
        this.scheduledTrigger = new ScheduledTrigger(defs);
        this.manualTrigger = new ManualTrigger();

        // 统一注册刷新处理器
        cdcTrigger.registerHandler(this::handleRefreshEvent);
        scheduledTrigger.registerHandler(this::handleRefreshEvent);
        manualTrigger.registerHandler(this::handleRefreshEvent);

        log.info("物化视图服务初始化完成，已注册 {} 个视图", viewRegistry.size());
    }

    /**
     * 启动所有触发器。
     */
    public void start() {
        if (started) {
            log.warn("物化视图服务已启动，忽略重复启动");
            return;
        }
        if (!config.isEnabled()) {
            log.info("物化视图功能未启用，跳过启动");
            started = true;
            return;
        }
        ensureInitialized();
        cdcTrigger.start();
        scheduledTrigger.start();
        manualTrigger.start();
        started = true;
        log.info("物化视图服务已启动");
    }

    /**
     * 停止所有触发器。
     */
    public void stop() {
        if (!started) {
            return;
        }
        if (cdcTrigger != null) {
            cdcTrigger.stop();
        }
        if (scheduledTrigger != null) {
            scheduledTrigger.stop();
        }
        if (manualTrigger != null) {
            manualTrigger.stop();
        }
        started = false;
        log.info("物化视图服务已停止");
    }

    /**
     * 确保服务已初始化。
     */
    private void ensureInitialized() {
        if (viewRefresher == null) {
            init();
        }
    }

    /**
     * 注册物化视图定义。
     *
     * @param def 物化视图定义
     * @return 是否注册成功（名称重复返回 false）
     */
    public boolean registerView(MaterializedViewDef def) {
        Objects.requireNonNull(def, "物化视图定义不能为 null");
        def.validate();
        String name = def.getName();
        if (viewRegistry.putIfAbsent(name, def) != null) {
            log.warn("物化视图已存在，注册失败: {}", name);
            return false;
        }
        log.info("注册物化视图: {}，目标: {}.{}", name, def.getDatabase(), def.getTargetTable());
        // 若服务已启动，需重新初始化触发器以纳入新视图
        if (started) {
            rebuildTriggers();
        }
        return true;
    }

    /**
     * 更新物化视图定义。
     *
     * @param def 新的视图定义
     * @return 是否更新成功（不存在返回 false）
     */
    public boolean updateView(MaterializedViewDef def) {
        Objects.requireNonNull(def, "物化视图定义不能为 null");
        def.validate();
        String name = def.getName();
        if (viewRegistry.replace(name, def) == null) {
            log.warn("物化视图不存在，更新失败: {}", name);
            return false;
        }
        log.info("更新物化视图: {}", name);
        if (started) {
            rebuildTriggers();
        }
        return true;
    }

    /**
     * 删除物化视图定义。
     *
     * @param viewName 视图名称
     * @return 是否删除成功（不存在返回 false）
     */
    public boolean removeView(String viewName) {
        MaterializedViewDef removed = viewRegistry.remove(viewName);
        if (removed == null) {
            return false;
        }
        log.info("删除物化视图: {}", viewName);
        if (started) {
            rebuildTriggers();
        }
        return true;
    }

    /**
     * 查询物化视图定义。
     *
     * @param viewName 视图名称
     * @return 视图定义；不存在返回 null
     */
    public MaterializedViewDef getView(String viewName) {
        return viewRegistry.get(viewName);
    }

    /**
     * 列出所有物化视图定义。
     *
     * @return 视图定义列表
     */
    public List<MaterializedViewDef> listViews() {
        return new ArrayList<>(viewRegistry.values());
    }

    /**
     * 处理 CDC 变更记录（供 Flink CDC 流处理算子调用）。
     *
     * @param record CDC 变更记录
     */
    public void onCdcChange(ChangeRecord record) {
        if (cdcTrigger != null && cdcTrigger.isRunning()) {
            cdcTrigger.onChange(record);
        }
    }

    /**
     * 手动触发物化视图刷新。
     *
     * @param viewName 视图名称
     * @param operator 操作人
     * @return 刷新事件；若触发失败返回 null
     */
    public RefreshEvent refreshManually(String viewName, String operator) {
        ensureInitialized();
        if (!viewRegistry.containsKey(viewName)) {
            log.warn("手动刷新失败，视图不存在: {}", viewName);
            return null;
        }
        return manualTrigger.trigger(viewName, operator);
    }

    /**
     * 统一处理刷新事件（由各触发器回调）。
     *
     * @param event 刷新事件
     */
    void handleRefreshEvent(RefreshEvent event) {
        if (viewRefresher != null) {
            viewRefresher.refresh(event);
        }
    }

    /**
     * 重建触发器（在视图定义变更后调用）。
     */
    private void rebuildTriggers() {
        log.info("重建物化视图触发器...");
        if (cdcTrigger != null) {
            cdcTrigger.stop();
        }
        if (scheduledTrigger != null) {
            scheduledTrigger.stop();
        }
        List<MaterializedViewDef> defs = new ArrayList<>(viewRegistry.values());
        cdcTrigger = new CdcChangeTrigger(defs);
        scheduledTrigger = new ScheduledTrigger(defs);
        cdcTrigger.registerHandler(this::handleRefreshEvent);
        scheduledTrigger.registerHandler(this::handleRefreshEvent);
        cdcTrigger.start();
        scheduledTrigger.start();
        log.info("物化视图触发器重建完成");
    }

    /**
     * 获取指定视图的最近刷新结果。
     *
     * @param viewName 视图名称
     * @return 刷新结果；未刷新过返回 null
     */
    public ViewRefresher.RefreshResult getLastRefreshResult(String viewName) {
        return viewRefresher == null ? null : viewRefresher.getLastResult(viewName);
    }

    /**
     * 获取当前活跃刷新数。
     *
     * @return 活跃刷新数
     */
    public int getActiveRefreshCount() {
        return viewRefresher == null ? 0 : viewRefresher.getActiveRefreshCount();
    }

    /**
     * 获取所有已注册视图名称 → 视图定义的映射（只读）。
     *
     * @return 不可修改的映射
     */
    public Map<String, MaterializedViewDef> getViewRegistry() {
        return Map.copyOf(viewRegistry);
    }

    /**
     * 判断服务是否已启动。
     *
     * @return 若已启动返回 true
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * 获取已注册视图数量。
     *
     * @return 视图数量
     */
    public int viewCount() {
        return viewRegistry.size();
    }
}