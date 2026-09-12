package com.levango7.dataenginebdp.governance.collector.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.governance.collector.collector.MetadataCollector;
import com.levango7.dataenginebdp.governance.collector.model.CollectionHistory;
import com.levango7.dataenginebdp.governance.collector.model.CollectionResult;
import com.levango7.dataenginebdp.governance.collector.model.MetadataSource;
import com.levango7.dataenginebdp.governance.collector.repository.MetadataSourceRepository;
import com.levango7.dataenginebdp.governance.collector.service.CollectionSchedulerService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 元数据采集 REST 控制器。
 *
 * <p>统一前缀：{@code /api/v1/metadata}</p>
 *
 * <p>端点清单：
 * <ul>
 *   <li>POST   /sources                       — 添加数据源</li>
 *   <li>GET    /sources                       — 列出当前租户数据源</li>
 *   <li>GET    /sources/{id}                  — 获取单个数据源</li>
 *   <li>PUT    /sources/{id}                  — 更新数据源</li>
 *   <li>DELETE /sources/{id}                  — 删除数据源</li>
 *   <li>POST   /collect/{sourceId}            — 手动触发采集</li>
 *   <li>GET    /collect/status/{sourceId}     — 查询采集状态</li>
 *   <li>POST   /collect/test/{sourceId}       — 测试数据源连接</li>
 *   <li>POST   /collect/schedule/{sourceId}   — 注册定时采集（cron）</li>
 *   <li>DELETE /collect/schedule/{sourceId}   — 取消定时采集</li>
 * </ul></p>
 *
 * <p><b>安全控制（R10 修复）</b>：
 * <ul>
 *   <li>类级 {@code @PreAuthorize("isAuthenticated()")}：所有端点要求认证，
 *       写操作（创建/更新/删除/触发采集/调度）进一步要求 {@code hasRole('GOVERNANCE_WRITER')}。</li>
 *   <li>租户隔离：从 {@link TenantContext} 读取当前租户 ID，缺失返回 403；
 *       查询/更新/删除按 (id, tenantId) 联合校验；创建时写入 tenantId。</li>
 * </ul></p>
 */
@RestController
@Tag(name = "数据治理-元数据采集", description = "数据源管理与元数据采集")
@RequestMapping("/api/v1/metadata")
@PreAuthorize("isAuthenticated()")
public class CollectorController {

    private static final Logger log = LoggerFactory.getLogger(CollectorController.class);

    private final MetadataSourceRepository sourceRepository;
    private final CollectionSchedulerService schedulerService;
    private final List<MetadataCollector> collectors;

    /**
     * 构造控制器。
     *
     * @param sourceRepository 数据源 Repository
     * @param schedulerService 采集调度服务
     * @param collectors       Spring 注入的所有 Collector 实现
     */
    public CollectorController(MetadataSourceRepository sourceRepository,
                               CollectionSchedulerService schedulerService,
                               List<MetadataCollector> collectors) {
        this.sourceRepository = sourceRepository;
        this.schedulerService = schedulerService;
        this.collectors = collectors;
    }

    // ============ 数据源 CRUD ============

    /**
     * 添加数据源（写入当前租户 ID）。
     *
     * @param source 数据源配置
     * @return 创建后的数据源（含 ID），201 状态码
     */
    @Operation(summary = "创建元数据")
    @PostMapping("/sources")
    @PreAuthorize("hasRole('GOVERNANCE_WRITER')")
    public ResponseEntity<MetadataSource> addSource(@Valid @RequestBody MetadataSource source) {
        String tenantId = requireTenant();
        source.setTenantId(tenantId);
        LocalDateTime now = LocalDateTime.now();
        source.setCreatedAt(now);
        source.setUpdatedAt(now);
        if (source.getStatus() == null) {
            source.setStatus("ACTIVE");
        }
        MetadataSource saved = sourceRepository.save(source);
        // 若提供 cron，自动注册调度
        if (source.getCron() != null && !source.getCron().isBlank()) {
            schedulerService.scheduleCollection(saved.getId(), source.getCron());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * 列出当前租户的全部数据源。
     *
     * @return 数据源列表
     */
    @Operation(summary = "列出当前租户数据源")
    @GetMapping("/sources")
    public ResponseEntity<List<MetadataSource>> listSources() {
        String tenantId = requireTenant();
        return ResponseEntity.ok(sourceRepository.findByTenantId(tenantId));
    }

    /**
     * 获取单个数据源（按租户隔离）。
     *
     * @param id 数据源 ID
     * @return 数据源；不存在或不属于当前租户返回 404
     */
    @Operation(summary = "获取单个数据源")
    @GetMapping("/sources/{id}")
    public ResponseEntity<MetadataSource> getSource(@PathVariable Long id) {
        String tenantId = requireTenant();
        return sourceRepository.findByIdAndTenantId(id, tenantId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 更新数据源（按租户隔离）。
     *
     * @param id     数据源 ID
     * @param source 新配置
     * @return 更新后的数据源；不存在或不属于当前租户返回 404
     */
    @Operation(summary = "更新元数据")
    @PutMapping("/sources/{id}")
    @PreAuthorize("hasRole('GOVERNANCE_WRITER')")
    public ResponseEntity<MetadataSource> updateSource(@PathVariable Long id,
                                                       @Valid @RequestBody MetadataSource source) {
        String tenantId = requireTenant();
        Optional<MetadataSource> existing = sourceRepository.findByIdAndTenantId(id, tenantId);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        source.setId(id);
        source.setTenantId(tenantId);
        source.setCreatedAt(existing.get().getCreatedAt());
        source.setUpdatedAt(LocalDateTime.now());
        MetadataSource saved = sourceRepository.save(source);
        // cron 变更时重新调度
        if (source.getCron() != null && !source.getCron().isBlank()) {
            schedulerService.scheduleCollection(id, source.getCron());
        } else {
            schedulerService.unscheduleCollection(id);
        }
        return ResponseEntity.ok(saved);
    }

    /**
     * 删除数据源（按租户隔离）。
     *
     * @param id 数据源 ID
     * @return 204；不存在或不属于当前租户返回 404
     */
    @Operation(summary = "删除元数据")
    @DeleteMapping("/sources/{id}")
    @PreAuthorize("hasRole('GOVERNANCE_WRITER')")
    public ResponseEntity<Void> deleteSource(@PathVariable Long id) {
        String tenantId = requireTenant();
        if (sourceRepository.findByIdAndTenantId(id, tenantId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        schedulerService.unscheduleCollection(id);
        sourceRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ============ 采集操作 ============

    /**
     * 手动触发指定数据源采集（按租户隔离）。
     *
     * @param sourceId 数据源 ID
     * @return 采集结果；数据源不存在或不属于当前租户返回 404
     */
    @Operation(summary = "手动触发指定数据源采集")
    @PostMapping("/collect/{sourceId}")
    @PreAuthorize("hasRole('GOVERNANCE_WRITER')")
    public ResponseEntity<CollectionResult> triggerCollection(@PathVariable Long sourceId) {
        String tenantId = requireTenant();
        if (sourceRepository.findByIdAndTenantId(sourceId, tenantId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<CollectionResult> result = schedulerService.triggerCollection(sourceId, "MANUAL");
        return result.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 查询指定数据源最近采集状态（按租户隔离）。
     *
     * @param sourceId 数据源 ID
     * @return 最近一条采集历史；无记录或不属于当前租户返回 404
     */
    @Operation(summary = "查询指定数据源最近采集状态")
    @GetMapping("/collect/status/{sourceId}")
    public ResponseEntity<CollectionHistory> getCollectionStatus(@PathVariable Long sourceId) {
        String tenantId = requireTenant();
        if (sourceRepository.findByIdAndTenantId(sourceId, tenantId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return schedulerService.getCollectionStatus(sourceId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 测试数据源连接（按租户隔离）。
     *
     * @param sourceId 数据源 ID
     * @return {@code {"connected": true/false}}；数据源不存在或不属于当前租户返回 404
     */
    @Operation(summary = "测试数据源连接")
    @PostMapping("/collect/test/{sourceId}")
    @PreAuthorize("hasRole('GOVERNANCE_WRITER')")
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable Long sourceId) {
        String tenantId = requireTenant();
        Optional<MetadataSource> sourceOpt = sourceRepository.findByIdAndTenantId(sourceId, tenantId);
        if (sourceOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        MetadataSource source = sourceOpt.get();
        MetadataCollector collector = findCollector(source.getType());
        Map<String, Object> body = new HashMap<>();
        body.put("sourceId", sourceId);
        body.put("sourceType", source.getType());
        if (collector == null) {
            body.put("connected", false);
            body.put("message", "No collector registered for type: " + source.getType());
            return ResponseEntity.ok(body);
        }
        boolean connected = collector.testConnection(source);
        body.put("connected", connected);
        body.put("message", connected ? "Connection successful" : "Connection failed");
        return ResponseEntity.ok(body);
    }

    /**
     * 注册定时采集（按租户隔离）。
     *
     * @param sourceId 数据源 ID
     * @param body     请求体，包含 {@code cron} 字段
     * @return 注册结果
     */
    @Operation(summary = "注册定时采集")
    @PostMapping("/collect/schedule/{sourceId}")
    @PreAuthorize("hasRole('GOVERNANCE_WRITER')")
    public ResponseEntity<Map<String, Object>> scheduleCollection(@PathVariable Long sourceId,
                                                                  @RequestBody Map<String, String> body) {
        String tenantId = requireTenant();
        if (sourceRepository.findByIdAndTenantId(sourceId, tenantId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String cron = body.get("cron");
        boolean success = schedulerService.scheduleCollection(sourceId, cron);
        Map<String, Object> resp = new HashMap<>();
        resp.put("sourceId", sourceId);
        resp.put("cron", cron);
        resp.put("scheduled", success);
        return ResponseEntity.status(success ? HttpStatus.OK : HttpStatus.BAD_REQUEST).body(resp);
    }

    /**
     * 取消定时采集（按租户隔离）。
     *
     * @param sourceId 数据源 ID
     * @return 取消结果
     */
    @Operation(summary = "取消定时采集")
    @DeleteMapping("/collect/schedule/{sourceId}")
    @PreAuthorize("hasRole('GOVERNANCE_WRITER')")
    public ResponseEntity<Map<String, Object>> unscheduleCollection(@PathVariable Long sourceId) {
        String tenantId = requireTenant();
        if (sourceRepository.findByIdAndTenantId(sourceId, tenantId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        boolean success = schedulerService.unscheduleCollection(sourceId);
        Map<String, Object> resp = new HashMap<>();
        resp.put("sourceId", sourceId);
        resp.put("unscheduled", success);
        return ResponseEntity.ok(resp);
    }

    /**
     * 列出已注册的 Collector 类型。
     *
     * <p>平台级内置资源，不按租户隔离。</p>
     *
     * @return 类型列表
     */
    @Operation(summary = "列出已注册的 Collector 类型")
    @GetMapping("/collectors")
    public ResponseEntity<List<String>> listCollectors() {
        return ResponseEntity.ok(schedulerService.getRegisteredTypes());
    }

    /**
     * 从 {@link TenantContext} 获取租户 ID，缺失则 fail-closed。
     *
     * @return 当前请求的租户 ID
     * @throws IllegalStateException 若 TenantContext 未设置租户 ID
     */
    private static String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    /**
     * 按 type 查找 Collector。
     *
     * @param type 数据源类型
     * @return Collector；未找到返回 null
     */
    private MetadataCollector findCollector(String type) {
        return collectors.stream()
                .filter(c -> c.getType().equalsIgnoreCase(type))
                .findFirst()
                .orElse(null);
    }
}
