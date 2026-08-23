package com.levango7.dataenginebdp.federated.scheduling;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
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
 */
@Slf4j
@RestController
@Tag(name = "多集群联邦-调度策略", description = "联邦调度决策与PropagationPolicy")
@RequestMapping("/api/v1/federated/scheduling")
public class SchedulingController {

    private final FederatedScheduler scheduler;

    public SchedulingController(FederatedScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * 创建/注册调度策略。
     *
     * <p>POST /api/v1/federated/scheduling/policies
     *
     * @param policy 调度策略
     * @return 注册后的策略
     */
    @Operation(summary = "创建/注册调度策略")
    @PostMapping("/policies")
    public ResponseEntity<Map<String, Object>> createPolicy(@Valid @RequestBody SchedulingPolicy policy) {
        log.info("Create scheduling policy: name={}, types={}", policy.getName(), policy.getPolicyTypes());
        SchedulingPolicy saved = scheduler.registerPolicy(policy);
        return ResponseEntity.ok(Map.of(
                "data", saved,
                "status", "created",
                "timestamp", Instant.now().toString()));
    }

    /**
     * 列出所有调度策略。
     *
     * <p>GET /api/v1/federated/scheduling/policies
     */
    @Operation(summary = "列出所有调度策略")
    @GetMapping("/policies")
    public ResponseEntity<Map<String, Object>> listPolicies() {
        List<SchedulingPolicy> policies = scheduler.listPolicies();
        return ResponseEntity.ok(Map.of(
                "data", policies,
                "total", policies.size(),
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
        log.info("Scheduling decide: workload={}, replicas={}, candidates={}",
                input.getWorkloadName(), input.getReplicas(),
                input.getCandidates() == null ? 0 : input.getCandidates().size());
        FederatedScheduler.SchedulingDecision decision = scheduler.decide(input);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", decision);
        body.put("success", decision.isSuccess());
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
        // 实际环境从 Karmada API 拉取集群拓扑，此处返回空视图由前端/客户端填充
        Map<String, Map<String, List<String>>> view = new LinkedHashMap<>();
        return ResponseEntity.ok(Map.of(
                "data", view,
                "filter", clusters == null ? "all" : clusters,
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
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        List<FederatedScheduler.SchedulingDecision> history = scheduler.listDecisions(limit);
        return ResponseEntity.ok(Map.of(
                "data", history,
                "total", history.size(),
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
        String policyName = request.get("policyName");
        if (policyName == null || policyName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "policyName is required",
                    "timestamp", Instant.now().toString()));
        }
        log.info("Generate PropagationPolicy YAML for: {}", policyName);
        String yaml = scheduler.generatePropagationPolicy(policyName);
        return ResponseEntity.ok(Map.of(
                "policyName", policyName,
                "yaml", yaml,
                "timestamp", Instant.now().toString()));
    }
}