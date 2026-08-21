package com.shuqing.bigdata.sqlgateway.virtual;

import com.shuqing.bigdata.sqlgateway.virtual.adapter.VirtualAdapter;
import com.shuqing.bigdata.sqlgateway.virtual.adapter.VirtualAdapterException;
import com.shuqing.bigdata.sqlgateway.virtual.adapter.VirtualAdapterRegistry;
import com.shuqing.bigdata.sqlgateway.virtual.materialize.MaterializationScheduler;
import com.shuqing.bigdata.sqlgateway.virtual.materialize.MaterializationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 虚拟表业务逻辑服务。
 *
 * <p>封装虚拟表的注册、查询、更新、删除与元数据缓存管理。
 * 所有操作均以租户 ID 为隔离边界，确保不同租户的虚拟表互不可见。</p>
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>CRUD 操作 + 元数据缓存维护；</li>
 *   <li>查询转发：通过适配器将查询下推到外部源；</li>
 *   <li>物化触发：注册/更新时按策略自动创建物化表；</li>
 *   <li>权限校验：通过 {@code TenantContext} 确保租户隔离。</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@Service
public class VirtualTableService {

    private static final Logger log = LoggerFactory.getLogger(VirtualTableService.class);

    private final VirtualTableRepository repository;
    private final VirtualTableMetadataCache metadataCache;
    private final VirtualAdapterRegistry adapterRegistry;
    private final MaterializationService materializationService;
    private final MaterializationScheduler materializationScheduler;

    /**
     * 构造服务。
     *
     * @param repository             虚拟表仓储
     * @param metadataCache          元数据缓存
     * @param adapterRegistry        适配器注册中心
     * @param materializationService 物化执行服务
     * @param materializationScheduler 物化调度器
     */
    public VirtualTableService(VirtualTableRepository repository,
                               VirtualTableMetadataCache metadataCache,
                               VirtualAdapterRegistry adapterRegistry,
                               MaterializationService materializationService,
                               MaterializationScheduler materializationScheduler) {
        this.repository = repository;
        this.metadataCache = metadataCache;
        this.adapterRegistry = adapterRegistry;
        this.materializationService = materializationService;
        this.materializationScheduler = materializationScheduler;
    }

    /**
     * 注册虚拟表。
     *
     * @param definition 虚拟表定义（tenantId、tableName、dataSourceType、connectionConfig、sourceObject 必填）
     * @return 已保存的虚拟表定义
     * @throws IllegalArgumentException 若参数非法
     * @throws IllegalStateException    若同租户下表名已存在
     */
    @Transactional
    public VirtualTableDefinition register(VirtualTableDefinition definition) {
        validate(definition);
        if (repository.existsByTenantIdAndTableName(definition.getTenantId(), definition.getTableName())) {
            throw new IllegalStateException(
                    "虚拟表已存在: tenant=" + definition.getTenantId() + " table=" + definition.getTableName());
        }
        Instant now = Instant.now();
        definition.setCreatedAt(now);
        definition.setUpdatedAt(now);
        if (definition.getEnabled() == null) {
            definition.setEnabled(true);
        }
        if (definition.getMaterializationStrategy() == null) {
            definition.setMaterializationStrategy("NONE");
        }
        VirtualTableDefinition saved = repository.save(definition);
        metadataCache.put(saved);
        log.info("虚拟表注册成功 tenant={} table={} type={}",
                saved.getTenantId(), saved.getTableName(), saved.getDataSourceType());

        // 若启用物化，立即触发首次刷新
        if (saved.needsMaterialization()) {
            try {
                materializationService.refresh(saved);
            } catch (Exception e) {
                log.warn("首次物化刷新失败 table={} err={}", saved.getTableName(), e.getMessage());
            }
        }
        return saved;
    }

    /**
     * 按租户与表名获取虚拟表定义（优先查缓存）。
     *
     * @param tenantId  租户 ID
     * @param tableName 虚拟表名
     * @return 虚拟表定义（若存在）
     */
    public Optional<VirtualTableDefinition> get(String tenantId, String tableName) {
        Optional<VirtualTableDefinition> cached = metadataCache.get(tenantId, tableName);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<VirtualTableDefinition> fromDb = repository.findByTenantIdAndTableName(tenantId, tableName);
        fromDb.ifPresent(metadataCache::put);
        return fromDb;
    }

    /**
     * 列出指定租户的全部虚拟表。
     *
     * @param tenantId 租户 ID
     * @return 虚拟表定义列表
     */
    public List<VirtualTableDefinition> list(String tenantId) {
        return repository.findByTenantId(tenantId);
    }

    /**
     * 列出指定租户与数据源类型的虚拟表。
     *
     * @param tenantId       租户 ID
     * @param dataSourceType 数据源类型
     * @return 虚拟表定义列表
     */
    public List<VirtualTableDefinition> listByType(String tenantId, DataSourceType dataSourceType) {
        return repository.findByTenantIdAndDataSourceType(tenantId, dataSourceType);
    }

    /**
     * 更新虚拟表定义。
     *
     * @param tenantId       租户 ID
     * @param tableName      虚拟表名
     * @param newDefinition  新字段值
     * @return 更新后的虚拟表定义（若存在）
     */
    @Transactional
    public Optional<VirtualTableDefinition> update(String tenantId, String tableName,
                                                   VirtualTableDefinition newDefinition) {
        Optional<VirtualTableDefinition> existing = repository.findByTenantIdAndTableName(tenantId, tableName);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        VirtualTableDefinition def = existing.get();
        if (newDefinition.getDataSourceType() != null) {
            def.setDataSourceType(newDefinition.getDataSourceType());
        }
        if (newDefinition.getConnectionConfig() != null) {
            def.setConnectionConfig(newDefinition.getConnectionConfig());
        }
        if (newDefinition.getSourceObject() != null) {
            def.setSourceObject(newDefinition.getSourceObject());
        }
        if (newDefinition.getColumns() != null) {
            def.setColumns(newDefinition.getColumns());
        }
        if (newDefinition.getMaterializationStrategy() != null) {
            def.setMaterializationStrategy(newDefinition.getMaterializationStrategy());
        }
        if (newDefinition.getRefreshIntervalSeconds() != null) {
            def.setRefreshIntervalSeconds(newDefinition.getRefreshIntervalSeconds());
        }
        if (newDefinition.getEnabled() != null) {
            def.setEnabled(newDefinition.getEnabled());
        }
        if (newDefinition.getDescription() != null) {
            def.setDescription(newDefinition.getDescription());
        }
        def.setUpdatedAt(Instant.now());
        VirtualTableDefinition saved = repository.save(def);
        metadataCache.invalidate(tenantId, tableName);
        metadataCache.put(saved);
        log.info("虚拟表更新成功 tenant={} table={}", tenantId, tableName);
        return Optional.of(saved);
    }

    /**
     * 删除虚拟表。
     *
     * @param tenantId  租户 ID
     * @param tableName 虚拟表名
     * @return {@code true} 表示删除成功
     */
    @Transactional
    public boolean delete(String tenantId, String tableName) {
        Optional<VirtualTableDefinition> existing = repository.findByTenantIdAndTableName(tenantId, tableName);
        if (existing.isEmpty()) {
            return false;
        }
        VirtualTableDefinition def = existing.get();
        // 删除物化表（若存在）
        if (def.needsMaterialization()) {
            materializationService.dropMaterializedTable(def);
        }
        repository.delete(def);
        metadataCache.invalidate(tenantId, tableName);
        log.info("虚拟表删除成功 tenant={} table={}", tenantId, tableName);
        return true;
    }

    /**
     * 查询虚拟表数据（通过适配器下推到外部源）。
     *
     * @param tenantId  租户 ID
     * @param tableName 虚拟表名
     * @param predicate 查询谓词（可选）
     * @param limit     行数上限（可选）
     * @return 查询结果
     * @throws VirtualAdapterException 若查询失败
     */
    public VirtualAdapter.QueryResult query(String tenantId, String tableName,
                                            String predicate, Integer limit) {
        VirtualTableDefinition def = get(tenantId, tableName)
                .orElseThrow(() -> new VirtualAdapterException("TABLE_NOT_FOUND",
                        "虚拟表不存在: " + tableName));
        if (!def.isAvailable()) {
            throw new VirtualAdapterException("TABLE_DISABLED",
                    "虚拟表未启用或无列定义: " + tableName);
        }
        VirtualAdapter adapter = adapterRegistry.getAdapter(def);
        return adapter.query(def, predicate, limit);
    }

    /**
     * 获取虚拟表 schema（优先缓存，miss 则通过适配器获取并缓存）。
     *
     * @param tenantId  租户 ID
     * @param tableName 虚拟表名
     * @return 列定义列表
     */
    public List<ColumnDefinition> getSchema(String tenantId, String tableName) {
        Optional<VirtualTableDefinition> cached = get(tenantId, tableName);
        if (cached.isEmpty()) {
            return List.of();
        }
        VirtualTableDefinition def = cached.get();
        if (def.getColumns() != null && !def.getColumns().isEmpty()) {
            return def.getColumns();
        }
        // 通过适配器获取
        try {
            VirtualAdapter adapter = adapterRegistry.getAdapter(def);
            List<ColumnDefinition> schema = adapter.getSchema(def);
            def.setColumns(schema);
            repository.save(def);
            metadataCache.put(def);
            return schema;
        } catch (Exception e) {
            log.warn("获取 schema 失败 table={} err={}", tableName, e.getMessage());
            return List.of();
        }
    }

    /**
     * 测试虚拟表连接。
     *
     * @param definition 虚拟表定义
     * @return {@code true} 表示连接正常
     */
    public boolean testConnection(VirtualTableDefinition definition) {
        try {
            VirtualAdapter adapter = adapterRegistry.getAdapter(definition);
            return adapter.testConnection(definition);
        } catch (Exception e) {
            log.warn("连接测试失败 table={} err={}", definition.getTableName(), e.getMessage());
            return false;
        }
    }

    /**
     * 手动刷新物化表。
     *
     * @param tenantId  租户 ID
     * @param tableName 虚拟表名
     * @return 刷新行数
     */
    public int refreshMaterialization(String tenantId, String tableName) {
        VirtualTableDefinition def = get(tenantId, tableName)
                .orElseThrow(() -> new IllegalArgumentException("虚拟表不存在: " + tableName));
        return materializationScheduler.manualRefresh(def);
    }

    /**
     * 获取元数据缓存统计信息。
     *
     * @return 统计信息 Map
     */
    public java.util.Map<String, Object> getCacheStats() {
        return metadataCache.getStats();
    }

    /**
     * 校验虚拟表定义必填字段。
     *
     * @param definition 虚拟表定义
     * @throws IllegalArgumentException 若必填字段缺失
     */
    private void validate(VirtualTableDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("虚拟表定义不能为空");
        }
        if (definition.getTenantId() == null || definition.getTenantId().isBlank()) {
            throw new IllegalArgumentException("租户 ID 不能为空");
        }
        if (definition.getTableName() == null || definition.getTableName().isBlank()) {
            throw new IllegalArgumentException("虚拟表名不能为空");
        }
        if (definition.getDataSourceType() == null) {
            throw new IllegalArgumentException("数据源类型不能为空");
        }
        if (definition.getConnectionConfig() == null || definition.getConnectionConfig().isBlank()) {
            throw new IllegalArgumentException("连接配置不能为空");
        }
        if (definition.getSourceObject() == null || definition.getSourceObject().isBlank()) {
            throw new IllegalArgumentException("外部源对象不能为空");
        }
    }
}