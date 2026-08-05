package com.shuqing.bigdata.governance.collector.controller;

import com.shuqing.bigdata.governance.collector.collector.MetadataCollector;
import com.shuqing.bigdata.governance.collector.model.CollectionHistory;
import com.shuqing.bigdata.governance.collector.model.CollectionResult;
import com.shuqing.bigdata.governance.collector.model.MetadataSource;
import com.shuqing.bigdata.governance.collector.repository.MetadataSourceRepository;
import com.shuqing.bigdata.governance.collector.service.CollectionSchedulerService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
 *   <li>GET    /sources                       — 列出全部数据源</li>
 *   <li>GET    /sources/{id}                  — 获取单个数据源</li>
 *   <li>PUT    /sources/{id}                  — 更新数据源</li>
 *   <li>DELETE /sources/{id}                  — 删除数据源</li>
 *   <li>POST   /collect/{sourceId}            — 手动触发采集</li>
 *   <li>GET    /collect/status/{sourceId}     — 查询采集状态</li>
 *   <li>POST   /collect/test/{sourceId}       — 测试数据源连接</li>
 *   <li>POST   /collect/schedule/{sourceId}   — 注册定时采集（cron）</li>
 *   <li>DELETE /collect/schedule/{sourceId}   — 取消定时采集</li>
 * </ul></p>
 */
@RestController
@RequestMapping("/api/v1/metadata")
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
     * 添加数据源。
     *
     * @param source 数据源配置
     * @return 创建后的数据源（含 ID），201 状态码
     */
    @PostMapping("/sources")
    public ResponseEntity<MetadataSource> addSource(@Valid @RequestBody MetadataSource source) {
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
     * 列出全部数据源。
     *
     * @return 数据源列表
     */
    @GetMapping("/sources")
    public ResponseEntity<List<MetadataSource>> listSources() {
        return ResponseEntity.ok(sourceRepository.findAll());
    }

    /**
     * 获取单个数据源。
     *
     * @param id 数据源 ID
     * @return 数据源；不存在返回 404
     */
    @GetMapping("/sources/{id}")
    public ResponseEntity<MetadataSource> getSource(@PathVariable Long id) {
        return sourceRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 更新数据源。
     *
     * @param id     数据源 ID
     * @param source 新配置
     * @return 更新后的数据源；不存在返回 404
     */
    @PutMapping("/sources/{id}")
    public ResponseEntity<MetadataSource> updateSource(@PathVariable Long id,
                                                       @Valid @RequestBody MetadataSource source) {
        Optional<MetadataSource> existing = sourceRepository.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        source.setId(id);
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
     * 删除数据源。
     *
     * @param id 数据源 ID
     * @return 204；不存在返回 404
     */
    @DeleteMapping("/sources/{id}")
    public ResponseEntity<Void> deleteSource(@PathVariable Long id) {
        if (!sourceRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        schedulerService.unscheduleCollection(id);
        sourceRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ============ 采集操作 ============

    /**
     * 手动触发指定数据源采集。
     *
     * @param sourceId 数据源 ID
     * @return 采集结果；数据源不存在返回 404
     */
    @PostMapping("/collect/{sourceId}")
    public ResponseEntity<CollectionResult> triggerCollection(@PathVariable Long sourceId) {
        Optional<CollectionResult> result = schedulerService.triggerCollection(sourceId, "MANUAL");
        return result.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 查询指定数据源最近采集状态。
     *
     * @param sourceId 数据源 ID
     * @return 最近一条采集历史；无记录返回 404
     */
    @GetMapping("/collect/status/{sourceId}")
    public ResponseEntity<CollectionHistory> getCollectionStatus(@PathVariable Long sourceId) {
        return schedulerService.getCollectionStatus(sourceId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 测试数据源连接。
     *
     * @param sourceId 数据源 ID
     * @return {@code {"connected": true/false}}；数据源不存在返回 404
     */
    @PostMapping("/collect/test/{sourceId}")
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable Long sourceId) {
        Optional<MetadataSource> sourceOpt = sourceRepository.findById(sourceId);
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
     * 注册定时采集。
     *
     * @param sourceId 数据源 ID
     * @param body     请求体，包含 {@code cron} 字段
     * @return 注册结果
     */
    @PostMapping("/collect/schedule/{sourceId}")
    public ResponseEntity<Map<String, Object>> scheduleCollection(@PathVariable Long sourceId,
                                                                  @RequestBody Map<String, String> body) {
        String cron = body.get("cron");
        boolean success = schedulerService.scheduleCollection(sourceId, cron);
        Map<String, Object> resp = new HashMap<>();
        resp.put("sourceId", sourceId);
        resp.put("cron", cron);
        resp.put("scheduled", success);
        return ResponseEntity.status(success ? HttpStatus.OK : HttpStatus.BAD_REQUEST).body(resp);
    }

    /**
     * 取消定时采集。
     *
     * @param sourceId 数据源 ID
     * @return 取消结果
     */
    @DeleteMapping("/collect/schedule/{sourceId}")
    public ResponseEntity<Map<String, Object>> unscheduleCollection(@PathVariable Long sourceId) {
        boolean success = schedulerService.unscheduleCollection(sourceId);
        Map<String, Object> resp = new HashMap<>();
        resp.put("sourceId", sourceId);
        resp.put("unscheduled", success);
        return ResponseEntity.ok(resp);
    }

    /**
     * 列出已注册的 Collector 类型。
     *
     * @return 类型列表
     */
    @GetMapping("/collectors")
    public ResponseEntity<List<String>> listCollectors() {
        return ResponseEntity.ok(schedulerService.getRegisteredTypes());
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