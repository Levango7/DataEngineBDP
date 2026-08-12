package com.levango7.dataenginebdp.tagengine.store.doris;

import com.levango7.dataenginebdp.tagengine.model.AudienceRequest;
import com.levango7.dataenginebdp.tagengine.model.AudienceResult;
import com.levango7.dataenginebdp.tagengine.model.BatchComputeResult;
import com.levango7.dataenginebdp.tagengine.model.ComputeRequest;
import com.levango7.dataenginebdp.tagengine.model.TagComputeResult;
import com.levango7.dataenginebdp.tagengine.model.TagDefinition;
import com.levango7.dataenginebdp.tagengine.model.TagDefinitionRequest;
import com.levango7.dataenginebdp.tagengine.model.TagQuery;
import com.levango7.dataenginebdp.tagengine.model.TagRule;
import com.levango7.dataenginebdp.tagengine.model.TagRuleRequest;
import com.levango7.dataenginebdp.tagengine.model.UserProfile;
import com.levango7.dataenginebdp.tagengine.store.TagStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link TagStore} 的 Doris 实现。
 *
 * <p>通过 JDBC 调用 Doris FE（MySQL 协议）执行标签宽表的读写。
 * 对应详细设计 §3 标签宽表、§5 人群圈选、§6 接口契约。</p>
 *
 * <p>通过 {@code app.tag-store.type=doris} 激活。
 * Doris FE 地址、凭据由 Helm Chart 通过环境变量注入：</p>
 * <ul>
 *   <li>{@code DORIS_JDBC_URL}  — 例如 jdbc:mysql://doris-fe:9030</li>
 *   <li>{@code DORIS_USERNAME}  — 用户名</li>
 *   <li>{@code DORIS_PASSWORD}  — 密码</li>
 * </ul>
 *
 * <p>本类为骨架实现：DDL/SQL 生成由 {@link DorisDdlManager} 与 {@link DorisSqlGenerator} 完成，
 * JDBC 调用通过 {@link DataSource}（生产环境由 Spring Boot 自动配置 Druid/HikariCP）执行。
 * 标签定义/规则元数据仍走 JPA（PostgreSQL），宽表数据走 Doris。</p>
 *
 * <p>注意：本类不参与默认测试（Mock 模式），通过 {@code @ConditionalOnProperty} 隔离。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.tag-store", name = "type", havingValue = "doris")
public class DorisTagStore implements TagStore {

    private static final Logger log = LoggerFactory.getLogger(DorisTagStore.class);

    private final DataSource dataSource;
    private final DorisSqlGenerator sqlGenerator;
    private final DorisDdlManager ddlManager;

    @Value("${app.doris.database:tag_db}")
    private String database;

    @Value("${app.doris.wide-table:dws_user_tag_wide}")
    private String wideTable;

    @Value("${app.doris.select-limit:100000}")
    private int selectLimit;

    @Value("${app.doris.query-timeout-ms:5000}")
    private int queryTimeoutMs;

    public DorisTagStore(DataSource dataSource,
                         DorisSqlGenerator sqlGenerator,
                         DorisDdlManager ddlManager) {
        this.dataSource = dataSource;
        this.sqlGenerator = sqlGenerator;
        this.ddlManager = ddlManager;
    }

    // ==================== 标签定义管理 ====================

    @Override
    public TagDefinition createTagDefinition(TagDefinitionRequest req) {
        // 标签定义元数据由 JPA 持久化（TagService 负责），此处仅负责宽表 DDL
        // 实际生产中由 TagService 调用本方法后追加 ALTER TABLE ADD COLUMN
        String tagId = "tag-" + UUID.randomUUID();
        TagDefinition def = TagDefinition.builder()
                .tagId(tagId)
                .tenantId(req.getTenantId())
                .name(req.getName())
                .displayName(req.getDisplayName() != null ? req.getDisplayName() : req.getName())
                .type(req.getType())
                .valueDomain(req.getValueDomain())
                .description(req.getDescription())
                .columnName(req.getColumnName())
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        String ddl = ddlManager.buildAddColumnDdl(database, wideTable,
                new DorisDdlManager.TagColumn(def.getColumnName(), ddlManager.deriveDorisType(def)));
        executeDdl(ddl);
        log.info("DorisTagStore.createTagDefinition: tagId={}, ddl executed", tagId);
        return def;
    }

    @Override
    public TagDefinition getTagDefinition(String tagId) {
        // 元数据由 JPA 负责，Doris 宽表不存元数据
        throw new UnsupportedOperationException("metadata lookup is handled by JPA TagDefinitionRepository");
    }

    @Override
    public List<TagDefinition> listTagDefinitions(String tenantId) {
        throw new UnsupportedOperationException("metadata lookup is handled by JPA TagDefinitionRepository");
    }

    @Override
    public boolean deleteTagDefinition(String tagId) {
        // 由 TagService 先查元数据拿 columnName，再调本方法删列
        // 此处仅作为占位，实际由 TagService 编排
        throw new UnsupportedOperationException("call TagService.deleteTagDefinition for orchestration");
    }

    /**
     * 删除标签宽表列（由 TagService 编排调用）。
     *
     * @param columnName 列名
     */
    public void dropColumn(String columnName) {
        String ddl = ddlManager.buildDropColumnDdl(database, wideTable, columnName);
        executeDdl(ddl);
    }

    // ==================== 标签规则管理 ====================

    @Override
    public TagRule createTagRule(String tagId, TagRuleRequest req) {
        // 规则元数据由 JPA 持久化
        throw new UnsupportedOperationException("rule metadata is handled by JPA TagRuleRepository");
    }

    @Override
    public List<TagRule> getTagRules(String tagId) {
        throw new UnsupportedOperationException("rule metadata is handled by JPA TagRuleRepository");
    }

    @Override
    public boolean deleteTagRule(String ruleId) {
        throw new UnsupportedOperationException("rule metadata is handled by JPA TagRuleRepository");
    }

    // ==================== 标签计算 ====================

    @Override
    public TagComputeResult computeTag(String tagId, ComputeRequest req) {
        // 标签计算由 Spark ETL 执行，结果通过 Stream Load 写入 Doris 宽表
        // 本方法仅作为触发入口的占位，实际由 ComputeService 调用 Spark 作业
        log.warn("DorisTagStore.computeTag: should be orchestrated by ComputeService via Spark ETL, tagId={}", tagId);
        return TagComputeResult.builder()
                .tagId(tagId)
                .status("RUNNING")
                .tagVersion("v" + System.currentTimeMillis())
                .costMs(0)
                .build();
    }

    @Override
    public BatchComputeResult batchCompute(List<String> tagIds, ComputeRequest req) {
        long start = System.currentTimeMillis();
        List<TagComputeResult> results = new ArrayList<>();
        for (String tagId : tagIds) {
            results.add(computeTag(tagId, req));
        }
        return BatchComputeResult.builder()
                .results(results)
                .successCount(results.size())
                .failedCount(0)
                .totalCostMs(System.currentTimeMillis() - start)
                .build();
    }

    // ==================== 画像查询 ====================

    @Override
    public UserProfile getProfile(String userId) {
        // 简化：租户过滤由调用方保证
        DorisSqlGenerator.PreparedSql ps = sqlGenerator.buildProfileSql(wideTable, null, userId);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(ps.sql())) {
            bindParams(stmt, ps.params());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return mapRowToProfile(rs);
            }
        } catch (SQLException e) {
            log.error("DorisTagStore.getProfile failed: userId={}", userId, e);
            throw new RuntimeException("query profile failed", e);
        }
    }

    @Override
    public List<UserProfile> queryByTags(TagQuery query) {
        int limit = selectLimit;
        DorisSqlGenerator.PreparedSql ps = sqlGenerator.buildSelectSql(wideTable, query, limit, 0);
        List<UserProfile> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(ps.sql())) {
            bindParams(stmt, ps.params());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRowToProfile(rs));
                }
            }
        } catch (SQLException e) {
            log.error("DorisTagStore.queryByTags failed", e);
            throw new RuntimeException("query by tags failed", e);
        }
        return result;
    }

    @Override
    public long countByTags(TagQuery query) {
        DorisSqlGenerator.PreparedSql ps = sqlGenerator.buildCountSql(wideTable, query);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(ps.sql())) {
            bindParams(stmt, ps.params());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            log.error("DorisTagStore.countByTags failed", e);
            throw new RuntimeException("count by tags failed", e);
        }
        return 0;
    }

    // ==================== 人群圈选 ====================

    @Override
    public AudienceResult selectAudience(AudienceRequest req) {
        long start = System.currentTimeMillis();
        TagQuery effective = req.getInclude();
        long count = countByTags(effective);
        List<String> ids = null;
        boolean truncated = false;
        if (req.isReturnIds()) {
            int limit = req.getLimit() != null ? Math.min(req.getLimit(), selectLimit) : selectLimit;
            int offset = req.getOffset() != null ? req.getOffset() : 0;
            DorisSqlGenerator.PreparedSql ps = sqlGenerator.buildSelectSql(wideTable, effective, limit, offset);
            ids = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(ps.sql())) {
                bindParams(stmt, ps.params());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString("user_id"));
                    }
                }
            } catch (SQLException e) {
                log.error("DorisTagStore.selectAudience query ids failed", e);
                throw new RuntimeException("select audience failed", e);
            }
            truncated = count > limit;
        }
        return AudienceResult.builder()
                .count(count)
                .userIds(ids)
                .truncated(truncated)
                .costMs(System.currentTimeMillis() - start)
                .build();
    }

    // ==================== 内部辅助 ====================

    /**
     * 执行 DDL（建表/加列/删列）。
     */
    private void executeDdl(String ddl) {
        log.debug("DorisTagStore executeDdl: {}", ddl);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(ddl)) {
            stmt.execute();
        } catch (SQLException e) {
            log.error("DorisTagStore executeDdl failed: {}", ddl, e);
            throw new RuntimeException("ddl execution failed: " + ddl, e);
        }
    }

    /**
     * 绑定参数到 PreparedStatement。
     */
    private void bindParams(PreparedStatement stmt, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            stmt.setObject(i + 1, params.get(i));
        }
    }

    /**
     * 将 ResultSet 当前行映射为 UserProfile。
     */
    private UserProfile mapRowToProfile(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int n = meta.getColumnCount();
        Map<String, Object> tags = new HashMap<>();
        String userId = null;
        String tenantId = null;
        String tagVersion = null;
        LocalDateTime updateTs = null;
        for (int i = 1; i <= n; i++) {
            String col = meta.getColumnLabel(i);
            Object val = rs.getObject(i);
            switch (col) {
                case "user_id" -> userId = val == null ? null : String.valueOf(val);
                case "tenant_id" -> tenantId = val == null ? null : String.valueOf(val);
                case "tag_version" -> tagVersion = val == null ? null : String.valueOf(val);
                case "update_ts" -> updateTs = rs.getTimestamp(i) == null ? null
                        : rs.getTimestamp(i).toLocalDateTime();
                default -> tags.put(col, val);
            }
        }
        return UserProfile.builder()
                .userId(userId)
                .tenantId(tenantId)
                .tags(tags)
                .tagVersion(tagVersion)
                .updateTs(updateTs)
                .build();
    }
}