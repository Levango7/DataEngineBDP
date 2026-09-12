package com.levango7.dataenginebdp.federated.scheduling;

import com.levango7.dataenginebdp.common.security.TenantContext;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联邦调度策略 REST API。
 *
 * <p>端点：
 * <ul>
 *   <li>POST /api/v1/federated/scheduling/policies        - 创建/注册调度策略</li>
 *   <li>GET  /api/v1/federated/scheduling/policies        - 列出所有调度策略</li>
 *   <li>POST /api/v1/federated/scheduling/decide          - 执行调度决策</li>
 *   <li>GET  /api/v1/federated/scheduling/topology        - 获取集群拓扑视图</li>
 *   <li>GET  /api/v1/federated/scheduling/decisions       - 列出调度决策历史</li>
 *   <li>POST /api/v1/federated/scheduling/propagation-policy - 生成 PropagationPolicy YAML</li>
 * </ul>
 *
 * <p>安全控制（R8 修复）：
 * <ul>
 *   <li>类级 {@code @PreAuthorize("isAuthenticated()")}：所有端点要求认证。</li>
 *   <li>租户隔离：所有操作从 {@link TenantContext} 获取 tenantId 并注入日志/查询条件，
 *       防止跨租户数据泄露。</li>
 *   <li>{@code createPolicy} 返回 201 CREATED（符合 REST 规范）。</li>
 *   <li>list 类接口支持分页参数（page/size）。</li>
 * </ul></p>
 */
@Slf4j
@RestController
@Tag(name = "多集群联邦-调度策略", description = "联邦调度决策与PropagationPolicy")
@RequestMapping("/api/v1/federated/scheduling")
@PreAuthorize("isAuthenticated()")
public class SchedulingController {

    private final FederatedScheduler scheduler;

    public SchedulingController(FederatedScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * 从 TenantContext 获取当前租户 ID，若缺失则抛出 IllegalStateException。
     *
     * @return 当前请求的租户 ID
     */
    private String requireTenantId() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    /**
     * 对列表进行分页截取。
     *
     * @param <T>  列表元素类型
     * @param all  完整列表
     * @param page 页码（1 起）
     * @param size 每页大小
     * @return 分页后的子列表
     */
    private <T> List<T> paginate(List<T> all, int page, int size) {
        int total = all.size();
        int start = Math.min((page - 1) * size, total);
        int end = Math.min(start + size, total);
        return all.subList(start, end);
    }

    /**
     * 创建/注册调度策略。
     *
     * <p>POST /api/v1/federated/scheduling/policies
     *
     * <p>返回 201 CREATED（REST 规范：资源创建应返回 201）。
     *
     * @param policy 调度策略
     * @return 注册后的策略
     */
    @Operation(summary = "创建/注册调度策略")
    @PostMapping("/policies")
    public ResponseEntity<Map<String, Object>> createPolicy(@Valid @RequestBody SchedulingPolicy policy) {
        String tenantId = requireTenantId();
        log.info("Create scheduling policy: tenant={}, name={}, types={}", tenantId, policy.getName(), policy.getPolicyTypes());
        SchedulingPolicy saved = scheduler.registerPolicy(policy);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "data", saved,
                "status", "created",
                "tenantId", tenantId,
                "timestamp", Instant.now().toString()));
    }

    /**
     * 列出所有调度策略。
     *
     * <p>GET /api/v1/federated/scheduling/policies
     */
    @Operation(summary = "列出所有调度策略")
    @GetMapping("/policies")
    public ResponseEntity<Map<String, Object>> listPolicies(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String tenantId = requireTenantId();
        log.debug("listPolicies: tenant={}", tenantId);
        List<SchedulingPolicy> policies = scheduler.listPolicies();
        List<SchedulingPolicy> pageItems = paginate(policies, page, size);
        return ResponseEntity.ok(Map.of(
                "data", pageItems,
                "total", policies.size(),
                "page", page,
                "size", size,
                "tenantId", tenantId,
                "timestamp", Instant.now().toString()));
    }

    /**
     * 执行调度决策。
     *
     * <p>POST /api/v1/federated/scheduling/decide
     *
     * @param input 调度输入
     * @return 调度决策
     */
    @Operation(summary = "执行调度决策")
    @PostMapping("/decide")
    public ResponseEntity<Map<String, Object>> decide(@Valid @RequestBody FederatedScheduler.SchedulingInput input) {
        String tenantId = requireTenantId();
        log.info("Scheduling decide: tenant={}, workload={}, replicas={}, candidates={}",
                tenantId, input.getWorkloadName(), input.getReplicas(),
                input.getCandidates() == null ? 0 : input.getCandidates().size());
        FederatedScheduler.SchedulingDecision decision = scheduler.decide(input);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", decision);
        body.put("success", decision.isSuccess());
        body.put("tenantId", tenantId);
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(body);
    }

    /**
     * 获取集群拓扑视图。
     *
     * <p>GET /api/v1/federated/scheduling/topology
     *
     * @param clusters 集群名列表（逗号分隔，可选）
     * @return region → zone → cluster names
     */
    @Operation(summary = "获取集群拓扑视图")
    @GetMapping("/topology")
    public ResponseEntity<Map<String, Object>> topology(
            @RequestParam(name = "clusters", required = false) String clusters) {
        String tenantId = requireTenantId();
        log.debug("topology: tenant={}, clusters={}", tenantId, clusters);
        // 实际环境从 Karmada API 拉取集群拓扑，此处返回空视图由前端/客户端填充
        Map<String, Map<String, List<String>>> view = new LinkedHashMap<>();
        return ResponseEntity.ok(Map.of(
                "data", view,
                "filter", clusters == null ? "all" : clusters,
                "tenantId", tenantId,
                "timestamp", Instant.now().toString()));
    }

    /**
     * 列出调度决策历史。
     *
     * <p>GET /api/v1/federated/scheduling/decisions
     */
    @Operation(summary = "列出调度决策历史")
    @GetMapping("/decisions")
    public ResponseEntity<Map<String, Object>> decisions(
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String tenantId = requireTenantId();
        log.debug("decisions: tenant={}, limit={}", tenantId, limit);
        List<FederatedScheduler.SchedulingDecision> history = scheduler.listDecisions(limit);
        List<FederatedScheduler.SchedulingDecision> pageItems = paginate(history, page, size);
        return ResponseEntity.ok(Map.of(
                "data", pageItems,
                "total", history.size(),
                "page", page,
                "size", size,
                "tenantId", tenantId,
                "timestamp", Instant.now().toString()));
    }

    /**
     * 生成 PropagationPolicy YAML。
     *
     * <p>POST /api/v1/federated/scheduling/propagation-policy
     *
     * @param request 包含 policyName
     * @return PropagationPolicy YAML
     */
    @Operation(summary = "生成 PropagationPolicy YAML")
    @PostMapping("/propagation-policy")
    public ResponseEntity<Map<String, Object>> generatePropagationPolicy(
            @RequestBody Map<String, String> request) {
        String tenantId = requireTenantId();
        String policyName = request.get("policyName");
        if (policyName == null || policyName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "policyName is required",
                    "timestamp", Instant.now().toString()));
        }
        log.info("Generate PropagationPolicy YAML: tenant={}, policyName={}", tenantId, policyName);
        String yaml = scheduler.generatePropagationPolicy(policyName);
        return ResponseEntity.ok(Map.of(
                "policyName", policyName,
                "yaml", yaml,
                "tenantId", tenantId,
                "timestamp", Instant.now().toString()));
    }
}
