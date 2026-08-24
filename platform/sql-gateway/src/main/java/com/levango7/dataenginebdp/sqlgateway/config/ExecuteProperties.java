package com.levango7.dataenginebdp.sqlgateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SQL 执行策略配置属性。
 *
 * <p>绑定配置前缀 {@code sql-gateway.execute}，集中管理执行安全门禁与结果集上限。</p>
 *
 * <p>典型 application.yml 用法：</p>
 * <pre>
 * sql-gateway:
 *   execute:
 *     read-only-only: true   # 拒绝 DML/DDL，仅放行 SELECT/SHOW/DESC/WITH/EXPLAIN
 *     default-limit: 1000    # 请求未指定 limit 时的默认行数上限
 *     max-rows: 10000        # 单次查询返回行数硬上限（防 OOM）
 * </pre>
 *
 * @author shuqing-bigdata
 */
@Data
@ConfigurationProperties(prefix = "sql-gateway.execute")
public class ExecuteProperties {

    /**
     * 是否仅允许只读 SQL。
     * <p>true 时非只读语句（INSERT/UPDATE/DELETE/DROP 等）在网关层直接拒绝，
     * 不下发后端。生产环境应保持 true；需要临时放开写入时显式置 false。</p>
     * <p>注意：类内默认 false 以兼容既有单测行为；application.yml 中已显式配置为 true。</p>
     */
    private boolean readOnlyOnly = false;

    /**
     * 请求未指定 limit 时的默认返回行数上限。{@code <=0} 表示使用请求值/硬上限。
     */
    private int defaultLimit = 1000;

    /**
     * 单次查询返回行数硬上限（含请求显式 limit），超出即截断并标记 truncated=true。
     * 防止全表结果集拉入网关内存导致 OOM。
     */
    private int maxRows = 10000;

    /**
     * 计算生效行数上限。
     *
     * @param requestLimit 请求显式指定的 limit（可空）
     * @return 生效上限；null 表示不限制（仅在 maxRows 与 defaultLimit 均关闭时可能出现）
     */
    public Integer effectiveLimit(Integer requestLimit) {
        int hard = Math.max(maxRows, 1);
        Integer cap = requestLimit != null && requestLimit > 0 ? requestLimit : null;
        if (defaultLimit > 0 && (cap == null || cap > defaultLimit)) {
            cap = defaultLimit;
        }
        if (cap == null || cap > hard) {
            cap = hard;
        }
        return cap;
    }
}
