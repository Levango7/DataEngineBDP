package com.levango7.dataenginebdp.sqlgateway.virtual.materialize;

import com.levango7.dataenginebdp.sqlgateway.virtual.VirtualTableDefinition;
import com.levango7.dataenginebdp.sqlgateway.virtual.adapter.VirtualAdapter;
import com.levango7.dataenginebdp.sqlgateway.virtual.adapter.VirtualAdapterRegistry;
import com.levango7.dataenginebdp.sqlgateway.virtual.VirtualTableRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 物化执行服务。
 *
 * <p>负责将虚拟表数据从外部源物化到本地表（存储于 SQL 网关所连数据库），
 * 支持 {@link MaterializationStrategy#FULL}（全量）与
 * {@link MaterializationStrategy#INCREMENTAL}（增量）两种刷新模式。</p>
 *
 * <p>物化表命名规则：{@code vt_materialized_<租户ID>_<虚拟表名>}，
 * 列定义与虚拟表一致，附加 {@code _vt_refresh_ts} 列记录刷新时间。</p>
 *
 * <p>刷新流程：</p>
 * <ol>
 *   <li>通过适配器从外部源拉取数据；</li>
 *   <li>FULL：先清空物化表，再批量写入；</li>
 *   <li>INCREMENTAL：仅写入 {@code _vt_refresh_ts} 大于上次刷新时间的行（若外部源支持）；</li>
 *   <li>更新虚拟表定义的 {@code lastRefreshTime}。</li>
 * </ol>
 *
 * @author shuqing-bigdata
 */
@Service
public class MaterializationService {

    private static final Logger log = LoggerFactory.getLogger(MaterializationService.class);

    private final VirtualTableRepository repository;
    private final VirtualAdapterRegistry adapterRegistry;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造服务。
     *
     * @param repository      虚拟表仓储
     * @param adapterRegistry 适配器注册中心
     * @param jdbcTemplate    JdbcTemplate（操作本地物化表）
     */
    public MaterializationService(VirtualTableRepository repository,
                                  VirtualAdapterRegistry adapterRegistry,
                                  JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.adapterRegistry = adapterRegistry;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 刷新指定虚拟表的物化数据。
     *
     * @param definition 虚拟表定义
     * @return 刷新行数
     */
    public int refresh(VirtualTableDefinition definition) {
        MaterializationStrategy strategy = MaterializationStrategy.fromString(
                definition.getMaterializationStrategy());
        if (strategy == MaterializationStrategy.NONE) {
            log.debug("虚拟表策略为 NONE，跳过刷新 table={}", definition.getTableName());
            return 0;
        }
        return switch (strategy) {
            case FULL -> refreshFull(definition);
            case INCREMENTAL -> refreshIncremental(definition);
            case MANUAL -> refreshFull(definition);
            default -> 0;
        };
    }

    /**
     * 全量刷新：清空物化表后重新写入全部数据。
     *
     * @param definition 虚拟表定义
     * @return 写入行数
     */
    private int refreshFull(VirtualTableDefinition definition) {
        log.info("全量刷新物化表 table={} tenant={}", definition.getTableName(), definition.getTenantId());
        String materializedTable = getMaterializedTableName(definition);
        try {
            // 1. 通过适配器拉取外部源数据
            VirtualAdapter adapter = adapterRegistry.getAdapter(definition);
            VirtualAdapter.QueryResult result = adapter.query(definition, null, null);

            // 2. 创建物化表（若不存在）
            createMaterializedTableIfNotExists(materializedTable, result.columns());

            // 3. 清空物化表
            jdbcTemplate.execute("DELETE FROM " + materializedTable);

            // 4. 批量写入
            int rowCount = batchInsert(materializedTable, result.columns(), result.rows());
            log.info("全量刷新完成 table={} rows={}", definition.getTableName(), rowCount);

            // 5. 更新刷新时间
            updateRefreshTime(definition);
            return rowCount;
        } catch (Exception e) {
            log.error("全量刷新失败 table={} err={}", definition.getTableName(), e.getMessage(), e);
            throw new RuntimeException("全量刷新失败: " + e.getMessage(), e);
        }
    }

    /**
     * 增量刷新：仅写入新增/变更数据。
     *
     * <p>当前简化实现：与全量刷新等效（外部源需提供变更标识才能实现真正增量）。
     * 生产环境可通过 CDC、时间戳列或版本号实现增量识别。</p>
     *
     * @param definition 虚拟表定义
     * @return 写入行数
     */
    private int refreshIncremental(VirtualTableDefinition definition) {
        log.info("增量刷新物化表 table={} tenant={} lastRefresh={}",
                definition.getTableName(), definition.getTenantId(), definition.getLastRefreshTime());
        // 简化实现：使用全量刷新
        return refreshFull(definition);
    }

    /**
     * 创建物化表（若不存在）。
     *
     * @param tableName 物化表名
     * @param columns   列名列表
     */
    private void createMaterializedTableIfNotExists(String tableName, List<String> columns) {
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        sql.append(tableName).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(columns.get(i)).append(" VARCHAR(4000)");
        }
        sql.append(", _vt_refresh_ts TIMESTAMP)");
        jdbcTemplate.execute(sql.toString());
        log.debug("物化表已创建/已存在 table={}", tableName);
    }

    /**
     * 批量插入数据。
     *
     * @param tableName 物化表名
     * @param columns   列名列表
     * @param rows      行数据
     * @return 插入行数
     */
    private int batchInsert(String tableName, List<String> columns, List<List<Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(tableName).append(" (");
        sql.append(String.join(", ", columns));
        sql.append(", _vt_refresh_ts) VALUES (");
        sql.append(String.join(", ", columns.stream().map(c -> "?").toList()));
        sql.append(", ?)");

        java.util.List<Object[]> batchArgs = new java.util.ArrayList<>();
        Instant now = Instant.now();
        java.sql.Timestamp ts = java.sql.Timestamp.from(now);
        for (List<Object> row : rows) {
            Object[] args = new Object[row.size() + 1];
            for (int i = 0; i < row.size(); i++) {
                args[i] = row.get(i) != null ? row.get(i).toString() : null;
            }
            args[row.size()] = ts;
            batchArgs.add(args);
        }
        jdbcTemplate.batchUpdate(sql.toString(), batchArgs);
        return rows.size();
    }

    /**
     * 更新虚拟表的最近刷新时间。
     *
     * @param definition 虚拟表定义
     */
    private void updateRefreshTime(VirtualTableDefinition definition) {
        definition.setLastRefreshTime(Instant.now());
        definition.setUpdatedAt(Instant.now());
        repository.save(definition);
    }

    /**
     * 生成物化表名。
     *
     * @param definition 虚拟表定义
     * @return 物化表名
     */
    public static String getMaterializedTableName(VirtualTableDefinition definition) {
        if (definition.getMaterializedTableName() != null
                && !definition.getMaterializedTableName().isBlank()) {
            return definition.getMaterializedTableName();
        }
        return "vt_materialized_" + sanitize(definition.getTenantId())
                + "_" + sanitize(definition.getTableName());
    }

    /**
     * 清理标识符中的特殊字符，确保可作为表名。
     *
     * @param identifier 原始标识符
     * @return 清理后的标识符
     */
    private static String sanitize(String identifier) {
        return identifier == null ? "unknown" : identifier.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /**
     * 删除物化表。
     *
     * @param definition 虚拟表定义
     */
    public void dropMaterializedTable(VirtualTableDefinition definition) {
        String tableName = getMaterializedTableName(definition);
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + tableName);
            log.info("物化表已删除 table={}", tableName);
        } catch (Exception e) {
            log.warn("物化表删除失败 table={} err={}", tableName, e.getMessage());
        }
    }
}