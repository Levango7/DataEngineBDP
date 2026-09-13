package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.repository.ApiDefinitionRepository;
import com.levango7.dataenginebdp.encaps.repository.AssetRepository;
import com.levango7.dataenginebdp.encaps.repository.StandardRepository;
import com.levango7.dataenginebdp.encaps.repository.TemplateRepository;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.service.ElasticsearchIndexer;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 检索门户端点（ROADMAP 前后端接线：前端 /search）。
 *
 * <p>ES 全文检索优先（本地 ES 容器 7.17 实测），ES 不可用时回退
 * 跨资产表 LIKE 检索。ES 索引由 {@link ElasticsearchIndexer} 维护，
 * 文档在资产/API/标准/模板写入时同步（见各 Controller 调用 index 方法）。</p>
 */
@Slf4j
@RestController
@Tag(name = "封装数据-检索门户", description = "全文检索与跨资产搜索")
@RequiredArgsConstructor
@RequestMapping("/api/v1/search")
@PreAuthorize("isAuthenticated()")  // R16 安全修复：类级认证校验
public class SearchController {

    private final AssetRepository assetRepository;
    private final ApiDefinitionRepository apiRepository;
    private final StandardRepository standardRepository;
    private final TemplateRepository templateRepository;
    private final ElasticsearchIndexer esIndexer;

    /**
     * 导出任务内存存储：taskId -> 任务元数据。
     *
     * <p>P2-5 已知限制：内存存储在多实例部署时不共享。
     * 生产环境应改用 Redis 或分布式缓存（需引入 spring-boot-starter-data-redis 依赖）。
     * 当前单实例部署下可接受，多实例时需迁移。</p>
     */
    private static final Map<String, Map<String, Object>> EXPORT_TASKS = new ConcurrentHashMap<>();

    /** 导出任务容量上限（防内存泄漏）。 */
    private static final int EXPORT_TASKS_MAX_SIZE = 500;

    /** 导出任务 TTL（30 分钟，过期自动清理）。 */
    private static final Duration EXPORT_TASK_TTL = Duration.ofMinutes(30);

    /** 检索历史内存存储：tenantId -> 历史记录列表（按时间倒序）。 */
    private static final Map<String, List<Map<String, Object>>> SEARCH_HISTORY = new ConcurrentHashMap<>();

    /** P3-3: 检索历史 TTL（1 小时，过期自动清理）。 */
    private static final Duration SEARCH_HISTORY_TTL = Duration.ofHours(1);

    /** P3-3: 检索历史最后清理时间。 */
    private volatile Instant searchHistoryLastCleanup = Instant.now();

    /** P3-26: 导出任务定时清理间隔（5 分钟）。 */
    private static final Duration EXPORT_CLEANUP_INTERVAL = Duration.ofMinutes(5);

    /** P3-26: 导出任务最后清理时间。 */
    private volatile Instant exportLastCleanup = Instant.now();

    /** P2-1: LIKE 回退检索单表分页大小（每个表最多加载条数，4 表共 10000 条，防 OOM）。 */
    private static final int LIKE_SEARCH_PAGE_SIZE = 2500;

    /** P3-1: 导出条数上限（可配置，默认 10000）。 */
    @Value("${app.search.export-limit:10000}")
    private int exportLimit;

    /** 检索请求体（对齐前端 SearchQuery 最小字段）。 */
    public record SearchRequest(
            String query,
            String mode,
            Integer page,
            Integer pageSize) {
    }

    /** 执行检索（ES 全文检索优先，不可用时回退 LIKE）。 */
    @Operation(summary = "执行检索（ES 全文检索优先，不可用时回退 LIKE）")
    @PostMapping
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> search(@RequestBody SearchRequest req) {
        String tenantId = requireTenant();
        Instant start = Instant.now();
        String q = req.query() == null ? "" : req.query().trim();
        int page = req.page() != null && req.page() > 0 ? Math.min(req.page(), 1000) : 1;
        // pageSize 上限 100，防超大分页拖垮查询
        int pageSize = req.pageSize() != null && req.pageSize() > 0
                ? Math.min(req.pageSize(), 100) : 20;
        int from = (page - 1) * pageSize;

        List<Map<String, Object>> results;
        long total = 0;
        boolean usedEs = false;
        if (!q.isEmpty() && esIndexer.isAvailable()) {
            usedEs = true;
            try {
                esIndexer.ensureIndex();
                // P1-5: 去掉搜索前全量同步，改为依赖各 Controller 写入时同步（ElasticsearchIndexer.indexDoc）
                // 增量同步由数据写入触发，搜索时不再全量同步，避免性能问题
                ElasticsearchIndexer.SearchResult sr =
                        esIndexer.search(q, from, pageSize);
                results = sr.list();
                total = sr.total();
            } catch (Exception e) {
                log.warn("ES 检索异常，回退 LIKE: {}", e.getMessage());
                // P2-3: 合并为单次 likeSearchAll 调用，避免重复全量加载
                List<Map<String, Object>> all = likeSearchAll(tenantId, q);
                total = all.size();
                results = likeSearchPage(all, from, pageSize);
            }
        } else {
            // P2-3: 合并为单次 likeSearchAll 调用，避免重复全量加载
            List<Map<String, Object>> all = likeSearchAll(tenantId, q);
            total = all.size();
            results = likeSearchPage(all, from, pageSize);
        }

        long tookMs = Duration.between(start, Instant.now()).toMillis();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("list", results);
        body.put("total", total);
        body.put("page", page);
        body.put("pageSize", pageSize);
        body.put("tookMs", tookMs);
        body.put("hasMore", (long) page * pageSize < total);
        body.put("suggestions", List.of());
        body.put("engine", usedEs ? "elasticsearch" : "like");

        // 记录检索历史到内存存储（按租户隔离，倒序保留最近 200 条）
        if (tenantId != null && !q.isEmpty()) {
            List<Map<String, Object>> records = SEARCH_HISTORY.computeIfAbsent(tenantId, k -> new ArrayList<>());
            synchronized (records) {
                Map<String, Object> hist = new LinkedHashMap<>();
                hist.put("id", UUID.randomUUID().toString());
                hist.put("query", q);
                hist.put("total", total);
                hist.put("createdAt", Instant.now().toString());
                records.add(0, hist);
                while (records.size() > 200) {
                    records.remove(records.size() - 1);
                }
            }
        }

        return ResponseEntity.ok(body);
    }

    /**

     * LIKE 回退检索（跨资产表，返回全量匹配结果）。
     *
     * <p>P2-1 修复：使用 Spring Data 分页查询，限制每个表最多加载 {@link #LIKE_SEARCH_PAGE_SIZE}
     * 条记录（4 个表共 10000 条），在数据库层面防 OOM。旧实现一次性全量加载 4 个表，
     * 租户数据量极大时可能导致内存溢出。</p>
     */
    private List<Map<String, Object>> likeSearchAll(String tenantId, String q) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (q == null || q.isEmpty()) {
            return results;
        }
        // P2-1: 分页查询，每个表最多加载 LIKE_SEARCH_PAGE_SIZE 条，数据库层面防 OOM
        PageRequest pageRequest = PageRequest.of(0, LIKE_SEARCH_PAGE_SIZE);
        assetRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageRequest).getContent().stream()
                .filter(a -> contains(a.getName(), q) || contains(a.getDescription(), q))
                .forEach(a -> results.add(Map.of(
                        "id", String.valueOf(a.getId()),
                        "name", a.getName(),
                        "type", a.getType(),
                        "source", "asset")));
        apiRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageRequest).getContent().stream()
                .filter(a -> contains(a.getName(), q) || contains(a.getPath(), q))
                .forEach(a -> results.add(Map.of(
                        "id", String.valueOf(a.getId()),
                        "name", a.getName(),
                        "type", "api",
                        "source", "api")));
        standardRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageRequest).getContent().stream()
                .filter(s -> contains(s.getName(), q) || contains(s.getRule(), q))
                .forEach(s -> results.add(Map.of(
                        "id", String.valueOf(s.getId()),
                        "name", s.getName(),
                        "type", s.getType(),
                        "source", "standard")));
        templateRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageRequest).getContent().stream()
                .filter(t -> contains(t.getName(), q) || contains(t.getDescription(), q))
                .forEach(t -> results.add(Map.of(
                        "id", String.valueOf(t.getId()),
                        "name", t.getName(),
                        "type", "template",
                        "source", "template")));
        return results;
    }

    /** 过滤器候选项（从各表聚合名称去重）。 */
    @Operation(summary = "过滤器候选项（从各表聚合名称去重）")
    @GetMapping("/facets")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> facets() {
        String tenantId = requireTenant();
        List<String> types = new ArrayList<>();
        assetRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                .forEach(a -> types.add("asset:" + a.getType()));
        types.add("api");
        types.add("standard");
        types.add("template");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sources", List.of(
                Map.of("value", "asset", "label", "数据资产"),
                Map.of("value", "api", "label", "API"),
                Map.of("value", "standard", "label", "标准"),
                Map.of("value", "template", "label", "模板")));
        body.put("types", types.stream().distinct().map(t -> Map.of("value", t, "label", t)).toList());
        body.put("tags", List.of());
        return ResponseEntity.ok(body);
    }

    /** 检索建议（基于名称前缀）。P3-15: 添加 @NotBlank 校验。 */
    @Operation(summary = "检索建议（基于名称前缀）")
    @GetMapping("/suggest")
    @Transactional(readOnly = true)
    public ResponseEntity<List<String>> suggest(@RequestParam @NotBlank(message = "keyword 不能为空") String keyword) {
        String tenantId = requireTenant();
        List<String> out = new ArrayList<>();
        if (keyword == null || keyword.isBlank()) {
            return ResponseEntity.ok(out);
        }
        String k = keyword.toLowerCase(Locale.ROOT);
        assetRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(a -> a.getName().toLowerCase(Locale.ROOT).contains(k))
                .limit(5).forEach(a -> out.add(a.getName()));
        apiRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(a -> a.getName().toLowerCase(Locale.ROOT).contains(k))
                .limit(3).forEach(a -> out.add(a.getName()));
        templateRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(t -> t.getName().toLowerCase(Locale.ROOT).contains(k))
                .limit(3).forEach(t -> out.add(t.getName()));
        return ResponseEntity.ok(out);
    }

    /** 检索历史（内存存储，按租户隔离，倒序返回最近 limit 条）。 */
    @Operation(summary = "检索历史（内存存储，按租户隔离，倒序返回最近 limit 条）")
    @GetMapping("/history")
    public ResponseEntity<List<Object>> history(@RequestParam(defaultValue = "20") int limit) {
        String tenantId = requireTenant();
        // P3-3: 定期清理过期的检索历史
        cleanupSearchHistory();
        List<Map<String, Object>> records = SEARCH_HISTORY.get(tenantId);
        if (records == null) {
            return ResponseEntity.ok(List.of());
        }
        int safeLimit = limit > 0 ? limit : 20;
        // R16 安全修复：synchronized 保护 subList 操作，避免并发修改异常
        // （search 方法在 synchronized(records) 块内会修改 records，此处读取需同步保护）
        List<Object> snapshot;
        synchronized (records) {
            int n = Math.min(safeLimit, records.size());
            snapshot = new ArrayList<>(records.subList(0, n));
        }
        return ResponseEntity.ok(snapshot);
    }

    /** P3-3: 清理过期的检索历史（TTL 机制，防内存泄漏）。 */
    private void cleanupSearchHistory() {
        Instant now = Instant.now();
        // 间隔检查（避免每次请求都清理）
        if (now.isBefore(searchHistoryLastCleanup.plus(EXPORT_CLEANUP_INTERVAL))) {
            return;
        }
        searchHistoryLastCleanup = now;
        Instant cutoff = now.minus(SEARCH_HISTORY_TTL);
        SEARCH_HISTORY.entrySet().removeIf(entry -> {
            List<Map<String, Object>> records = entry.getValue();
            synchronized (records) {
                records.removeIf(r -> {
                    String createdAtStr = String.valueOf(r.get("createdAt"));
                    try {
                        return Instant.parse(createdAtStr).isBefore(cutoff);
                    } catch (Exception e) {
                        return true; // 无法解析的视为过期
                    }
                });
                return records.isEmpty();
            }
        });
    }

    /**
     * 触发后端导出，返回下载链接。
     *
     * <p>对齐前端 {@code search.ts} 的 {@code exportResults}。
     * P1-4 修复：实际生成 CSV 内容并存储到内存，返回下载链接。
     * 异步导出：生成 UUID taskId，在当前线程同步生成 CSV（数据量小时开销低），
     * 大数据量时可改为 @Async + 临时文件。</p>
     *
     * @param req 导出请求
     * @return 200 + 导出结果
     */
    @Operation(summary = "触发后端导出，返回下载链接")
    @PostMapping("/export")
    public ResponseEntity<Map<String, Object>> export(@RequestBody Map<String, Object> req) {
        String tenantId = requireTenant();
        // 清理过期导出任务（TTL + 容量上限）
        cleanupExportTasks();
        String taskId = UUID.randomUUID().toString();
        log.info("触发检索导出: taskId={}, req={}, tenant={}", taskId, req, tenantId);

        // P1-4: 实际生成导出内容
        String query = req.getOrDefault("query", "").toString();
        String format = req.getOrDefault("format", "csv").toString();
        // P3: export 类型安全校验
        if (!"csv".equalsIgnoreCase(format) && !"json".equalsIgnoreCase(format)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "不支持的导出格式，仅支持 csv/json"));
        }

        // P1+P3-2: 一次性获取全量结果，内存中截断到上限（防 OOM + 数据一致性）
        // 旧实现循环调用 likeSearch（内部每次全量加载 likeSearchAll 再 subList），
        // 10 次循环反而加剧 OOM 且存在分页间数据一致性风险（P3-2）。
        // 改为单次调用 likeSearchAll，仅加载一次全量数据，再 subList 截断到上限。
        // P3-1: exportLimit 改为可配置（@Value("${app.search.export-limit:10000}")）
        List<Map<String, Object>> exportData = likeSearchAll(tenantId, query);
        if (exportData.size() > exportLimit) {
            exportData = new ArrayList<>(exportData.subList(0, exportLimit));
        }

        // P2-2: 根据 format 分别生成 CSV 或 JSON 内容
        String content;
        if ("json".equalsIgnoreCase(format)) {
            content = buildExportJson(exportData);
        } else {
            content = buildExportCsv(exportData);
        }

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskId", taskId);
        task.put("status", "completed");
        task.put("createdAt", Instant.now().toString());
        task.put("expiresAt", Instant.now().plus(EXPORT_TASK_TTL).toString());
        task.put("tenantId", tenantId);
        task.put("request", req);
        task.put("format", format);
        task.put("content", content);
        task.put("rowCount", exportData.size());
        EXPORT_TASKS.put(taskId, task);
        // 容量上限保护：超限时移除最早的任务
        while (EXPORT_TASKS.size() > EXPORT_TASKS_MAX_SIZE) {
            String oldest = EXPORT_TASKS.entrySet().stream()
                    .min(java.util.Comparator.comparing(e -> String.valueOf(e.getValue().get("createdAt"))))
                    .map(Map.Entry::getKey).orElse(null);
            if (oldest != null) {
                EXPORT_TASKS.remove(oldest);
            } else {
                break;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("downloadUrl", "/api/v1/search/export/" + taskId);
        result.put("status", "completed");
        result.put("rowCount", exportData.size());
        return ResponseEntity.ok(result);
    }

    /** P2-2: 生成 CSV 导出内容。 */
    private String buildExportCsv(List<Map<String, Object>> data) {
        StringBuilder csv = new StringBuilder();
        csv.append("id,name,type,source\n");
        for (Map<String, Object> row : data) {
            csv.append(escapeCsv(String.valueOf(row.getOrDefault("id", "")))).append(",")
                    .append(escapeCsv(String.valueOf(row.getOrDefault("name", "")))).append(",")
                    .append(escapeCsv(String.valueOf(row.getOrDefault("type", "")))).append(",")
                    .append(escapeCsv(String.valueOf(row.getOrDefault("source", "")))).append("\n");
        }
        return csv.toString();
    }

    /** P2-2: 生成 JSON 导出内容。 */
    private String buildExportJson(List<Map<String, Object>> data) {
        StringBuilder json = new StringBuilder();
        json.append("[");
        for (int i = 0; i < data.size(); i++) {
            Map<String, Object> row = data.get(i);
            if (i > 0) {
                json.append(",");
            }
            json.append("{");
            json.append("\"id\":\"").append(escapeJson(String.valueOf(row.getOrDefault("id", "")))).append("\"");
            json.append(",\"name\":\"").append(escapeJson(String.valueOf(row.getOrDefault("name", "")))).append("\"");
            json.append(",\"type\":\"").append(escapeJson(String.valueOf(row.getOrDefault("type", "")))).append("\"");
            json.append(",\"source\":\"").append(escapeJson(String.valueOf(row.getOrDefault("source", "")))).append("\"");
            json.append("}");
        }
        json.append("]");
        return json.toString();
    }

    /**
     * JSON 字符串转义（转义引号、反斜杠、换行及 U+0000~U+001F 控制字符）。
     *
     * <p>P3-3 修复：旧实现仅转义 {@code \" \\ \n \r \t}，遗漏 U+0000~U+001F 中
     * 其余控制字符（如 U+0000~U+0008、U+000B、U+000E~U+001F），这些字符在
     * JSON 规范（RFC 8259）中必须转义，否则生成的 JSON 非法，前端解析会失败。
     * 改为逐字符遍历，对全部控制字符（&lt; 0x20）按规范转义。</p>
     */
    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (ch < 0x20) {
                        // P3-3: 转义 U+0000~U+001F 中除已命名转义外的控制字符
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }
    /** CSV 字段转义（含逗号/引号/换行/回车时用双引号包裹）。 */
    private String escapeCsv(String field) {
        if (field == null) {
            return "";
        }
        // P3-3: 添加 \r 检查（RFC 4180 要求含换行符的字段加引号）
        if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }

    /**
     * LIKE 回退检索（跨资产表，分页返回当前页结果）。
     *
     * <p>P1-2 修复：添加 from/size 分页参数，仅返回当前页结果而非全量。</p>
     *
     * @param tenantId 租户 ID
     * @param q        搜索关键词
     * @param from     起始偏移量
     * @param size     每页大小
     * @return 当前页的搜索结果
     */
    private List<Map<String, Object>> likeSearch(String tenantId, String q, int from, int size) {
        List<Map<String, Object>> all = likeSearchAll(tenantId, q);
        return likeSearchPage(all, from, size);
    }

    /**
     * 从已加载的全量结果中截取当前页（P2-3: 提取公共分页逻辑，避免重复全量加载）。
     *
     * @param all  全量匹配结果
     * @param from 起始偏移量
     * @param size 每页大小
     * @return 当前页的搜索结果
     */
    private List<Map<String, Object>> likeSearchPage(List<Map<String, Object>> all, int from, int size) {
        if (from >= all.size()) {
            return List.of();
        }
        int to = Math.min(from + size, all.size());
        return all.subList(from, to);
    }

    /**
     * LIKE 回退检索的总匹配数（用于分页 total 字段）。
     *
     * @param tenantId 租户 ID
     * @param q        搜索关键词
     * @return 总匹配数
     */
    private long likeSearchTotal(String tenantId, String q) {
        return likeSearchAll(tenantId, q).size();
    }

    /**
     * 下载导出文件。
     *
     * <p>P1-4 修复：添加下载接口，返回 CSV 内容。</p>
     *
     * @param taskId 任务 ID
     * @return CSV 文件
     */
    @Operation(summary = "下载导出文件")
    @GetMapping("/export/{taskId}")
    public ResponseEntity<?> downloadExport(@PathVariable String taskId) {
        String tenantId = requireTenant();
        Map<String, Object> task = EXPORT_TASKS.get(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        // 租户隔离：仅允许下载本租户的导出
        if (!tenantId.equals(task.get("tenantId"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "无权访问此导出任务"));
        }
        String content = String.valueOf(task.getOrDefault("content", ""));
        String format = String.valueOf(task.getOrDefault("format", "csv"));
        // 清理过期任务
        cleanupExportTasks();
        if ("json".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"export-" + taskId + ".json\"")
                    .body(content);
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"export-" + taskId + ".csv\"")
                .body(content);
    }

    /**
     * 取消导出任务。
     *
     * <p>P3 修复：添加导出取消接口。</p>
     *
     * @param taskId 任务 ID
     * @return 200
     */
    @Operation(summary = "取消导出任务")
    @PostMapping("/export/{taskId}/cancel")
    public ResponseEntity<?> cancelExport(@PathVariable String taskId) {
        String tenantId = requireTenant();
        Map<String, Object> task = EXPORT_TASKS.get(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        if (!tenantId.equals(task.get("tenantId"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "无权取消此导出任务"));
        }
        EXPORT_TASKS.remove(taskId);
        log.info("取消导出任务: taskId={}, tenant={}", taskId, tenantId);
        return ResponseEntity.ok(Map.of("cancelled", true));
    }

    /** 清理过期导出任务（TTL 机制，防内存泄漏，P3-26: 间隔清理）。 */
    private void cleanupExportTasks() {
        Instant now = Instant.now();
        // P3-26: 间隔清理（避免每次请求都清理）
        if (now.isBefore(exportLastCleanup.plus(EXPORT_CLEANUP_INTERVAL))) {
            return;
        }
        exportLastCleanup = now;
        EXPORT_TASKS.entrySet().removeIf(entry -> {
            String expiresAtStr = String.valueOf(entry.getValue().get("expiresAt"));
            try {
                Instant expiresAt = Instant.parse(expiresAtStr);
                return now.isAfter(expiresAt);
            } catch (Exception e) {
                return true; // 无法解析的视为过期
            }
        });
    }

    /**
     * 清空检索历史。
     *
     * <p>对齐前端 {@code search.ts} 的 {@code clearHistory}（POST 方法）。
     * 清空当前租户的内存检索历史。</p>
     *
     * @return 200
     */
    @Operation(summary = "清空检索历史")
    @PostMapping("/history/clear")
    public ResponseEntity<Void> clearHistory() {
        String tenantId = requireTenant();
        SEARCH_HISTORY.remove(tenantId);
        log.info("清空检索历史: tenant={}", tenantId);
        return ResponseEntity.ok().build();
    }

    /**
     * 删除单条检索历史。
     *
     * <p>对齐前端 {@code search.ts} 的 {@code deleteHistory}（POST 方法）。
     * 从当前租户的内存历史列表中删除指定 ID 的记录。</p>
     *
     * @param id 历史 ID
     * @return 200
     */
    @Operation(summary = "删除单条检索历史")
    @PostMapping("/history/{id}/delete")
    public ResponseEntity<Void> deleteHistory(@PathVariable String id) {
        String tenantId = requireTenant();
        List<Map<String, Object>> records = SEARCH_HISTORY.get(tenantId);
        if (records != null) {
            synchronized (records) {
                records.removeIf(r -> id.equals(String.valueOf(r.get("id"))));
            }
        }
        log.info("删除检索历史: id={}, tenant={}", id, tenantId);
        return ResponseEntity.ok().build();
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    /**
     * 解析租户 ID：fail-closed 租户校验（R14 安全修复）。
     *
     * <p>仅信任 TenantContext（来自 JWT），若未设置则拒绝请求（返回 403），
     * 不回退到默认值，避免未认证请求绕过租户隔离。</p>
     *
     * @return 租户 ID
     * @throws IllegalStateException 若 TenantContext 未设置租户 ID
     */
    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    /**
     * 异常处理：缺少租户上下文返回 403（R14 安全修复）。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        log.warn("检索操作被拒绝: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "forbidden", "message", e.getMessage()));
    }
}
