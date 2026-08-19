package com.levango7.dataenginebdp.sqlgateway.virtual.rewrite;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.sqlgateway.virtual.VirtualTableDefinition;
import com.levango7.dataenginebdp.sqlgateway.virtual.VirtualTableService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 虚拟表 SQL 重写器。
 *
 * <p>在 SQL 网关执行 SQL 前，识别 SQL 中引用的虚拟表，将其重写为对外部源的查询。
 * 与现有 {@code CalciteOptimizer} 和 {@code ViewRewriter} 集成：</p>
 *
 * <ol>
 *   <li>解析 SQL 提取全部表名引用；</li>
 *   <li>对每个表名查询虚拟表定义（命中则为虚拟表）；</li>
 *   <li>若全部为虚拟表且物化策略非 NONE，则将表名重写为物化表名；</li>
 *   <li>若存在非物化虚拟表，则标记为需通过适配器下推查询；</li>
 *   <li>返回重写后 SQL 与涉及的虚拟表列表，供执行器决策。</li>
 * </ol>
 *
 * <p>本组件不直接执行 SQL，仅做静态分析与重写，保持与现有优化器链的解耦。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class VirtualTableRewriter {

    private static final Logger log = LoggerFactory.getLogger(VirtualTableRewriter.class);

    /**
     * 匹配 SQL 中的表引用（FROM/JOIN 后的标识符，支持 schema.table 形式）。
     * 简化实现：不处理子查询、CTE、引号包裹的标识符等复杂场景。
     */
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "(?i)\\b(?:FROM|JOIN)\\s+([a-zA-Z_][a-zA-Z0-9_.]*)");

    private final VirtualTableService virtualTableService;

    /**
     * 构造重写器。
     *
     * @param virtualTableService 虚拟表服务
     */
    public VirtualTableRewriter(VirtualTableService virtualTableService) {
        this.virtualTableService = virtualTableService;
    }

    /**
     * 重写 SQL 中的虚拟表引用。
     *
     * @param sql 原始 SQL
     * @return 重写结果
     */
    public RewriteResult rewrite(String sql) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return new RewriteResult(sql, false, List.of(), "无租户上下文，跳过重写");
        }
        Set<String> tableNames = extractTableNames(sql);
        if (tableNames.isEmpty()) {
            return new RewriteResult(sql, false, List.of(), "SQL 中未识别到表引用");
        }

        List<VirtualTableDefinition> virtualTables = new ArrayList<>();
        String rewrittenSql = sql;
        boolean rewritten = false;
        for (String tableName : tableNames) {
            Optional<VirtualTableDefinition> def = virtualTableService.get(tenantId, tableName);
            if (def.isEmpty()) {
                continue;
            }
            VirtualTableDefinition vt = def.get();
            virtualTables.add(vt);
            // 若虚拟表已物化，将表名重写为物化表名
            if (vt.needsMaterialization() && vt.getMaterializedTableName() != null) {
                String materializedName = com.levango7.dataenginebdp.sqlgateway.virtual.materialize
                        .MaterializationService.getMaterializedTableName(vt);
                rewrittenSql = rewrittenSql.replaceAll("(?i)\\b" + Pattern.quote(tableName) + "\\b",
                        materializedName);
                rewritten = true;
                log.debug("虚拟表重写 {} -> {} (物化表)", tableName, materializedName);
            }
        }
        if (virtualTables.isEmpty()) {
            return new RewriteResult(sql, false, List.of(), "未命中任何虚拟表");
        }
        String reason = rewritten
                ? "已将 " + virtualTables.size() + " 个虚拟表重写为物化表"
                : "命中 " + virtualTables.size() + " 个虚拟表（需通过适配器查询）";
        return new RewriteResult(rewrittenSql, rewritten, virtualTables, reason);
    }

    /**
     * 检查 SQL 是否引用了虚拟表。
     *
     * @param sql SQL 语句
     * @return 涉及的虚拟表定义列表（空表示无虚拟表）
     */
    public List<VirtualTableDefinition> findVirtualTables(String sql) {
        return rewrite(sql).virtualTables();
    }

    /**
     * 从 SQL 中提取表名引用。
     *
     * @param sql SQL 语句
     * @return 表名集合（去重）
     */
    private Set<String> extractTableNames(String sql) {
        Set<String> tables = new HashSet<>();
        Matcher matcher = TABLE_PATTERN.matcher(sql);
        while (matcher.find()) {
            String full = matcher.group(1);
            // 取最后一段作为表名（schema.table → table）
            String[] parts = full.split("\\.");
            tables.add(parts[parts.length - 1]);
        }
        return tables;
    }

    /**
     * 重写结果。
     *
     * @param sql           重写后 SQL（未重写则与原始相同）
     * @param rewritten     是否发生重写
     * @param virtualTables 涉及的虚拟表定义列表
     * @param reason        重写决策说明
     */
    public record RewriteResult(String sql, boolean rewritten,
                                List<VirtualTableDefinition> virtualTables,
                                String reason) {
    }
}