package com.shuqing.bigdata.streambatch.router;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * BI 视图路由器配置。
 *
 * <p>配置批快照视图与流最新视图的命名规则、Doris 物化视图集成参数等。
 *
 * <p>视图命名约定：
 * <ul>
 *   <li>批快照视图：{@code <table>_batch_v}（读 Iceberg 固定 snapshot）</li>
 *   <li>流最新视图：{@code <table>_stream_v}（读 Iceberg 最新 snapshot）</li>
 *   <li>Doris 物化视图：{@code <table>_mv}（与 Phase 1 T016 对齐）</li>
 * </ul>
 */
@Data
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "shuqing.stream-batch.router")
public class ViewRouterConfig {

    /** 批快照视图后缀。 */
    private String batchViewSuffix = "_batch_v";

    /** 流最新视图后缀。 */
    private String streamViewSuffix = "_stream_v";

    /** Doris 物化视图后缀（与 Phase 1 T016 对齐）。 */
    private String materializedViewSuffix = "_mv";

    /** Doris FE REST 地址（用于物化视图刷新与查询）。 */
    private String dorisFeRest = "http://localhost:8030";

    /** Doris 用户名。 */
    private String dorisUser = "root";

    /** Doris 密码。 */
    private String dorisPassword = "";

    /** 默认查询模式（AUTO 时使用的回退模式）。 */
    private QueryMode defaultQueryMode = QueryMode.OFFLINE;

    /**
     * AUTO 模式实时查询延迟阈值（毫秒）。
     * <p>查询要求延迟低于此值时选流最新视图，否则选批快照视图。
     */
    private long realtimeLatencyThresholdMs = 5000;

    /**
     * 表 → 物化视图配置（物化视图名、刷新策略）。
     * <p>与 Phase 1 T016 Doris 物化视图对齐。
     */
    private Map<String, MaterializedViewEntry> materializedViews = new HashMap<>();

    /**
     * Doris 物化视图配置项。
     */
    @Data
    public static class MaterializedViewEntry {
        /** 物化视图名。 */
        private String viewName;
        /** 基表名（Iceberg 表）。 */
        private String baseTable;
        /** 刷新模式（AUTO / MANUAL / SYNC）。 */
        private String refreshMode = "AUTO";
        /** 是否启用。 */
        private boolean enabled = true;
    }
}