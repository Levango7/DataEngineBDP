package com.levango7.dataenginebdp.federated.controller;

import com.levango7.dataenginebdp.federated.model.DegradationAlert;
import com.levango7.dataenginebdp.federated.model.FederatedQueryRequest;
import com.levango7.dataenginebdp.federated.model.FederatedQueryResponse;
import com.levango7.dataenginebdp.federated.service.FederatedQueryService;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 跨集群查询 REST API。
 */
@Slf4j
@RestController
@Tag(name = "多集群联邦-跨集群查询", description = "跨集群SQL查询与降级路由")
@RequestMapping("/api/v1/federated")
public class FederatedQueryController {

    private final FederatedQueryService service;

    public FederatedQueryController(FederatedQueryService service) {
        this.service = service;
    }

    /**
     * 提交跨集群查询（异步）。
     *
     * <p>POST /api/v1/federated/query
     */
    @Operation(summary = "提交跨集群查询（异步）")
    @PostMapping("/query")
    public CompletableFuture<ResponseEntity<FederatedQueryResponse>> query(@Valid @RequestBody FederatedQueryRequest request) {
        log.info("Federated query submitted: sql={} database={}", abbreviate(request.getSql()), request.getDatabase());
        return service.executeAsync(request)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * 同步跨集群查询。
     *
     * <p>POST /api/v1/federated/query/sync
     */
    @Operation(summary = "同步跨集群查询")
    @PostMapping("/query/sync")
    public ResponseEntity<FederatedQueryResponse> querySync(@Valid @RequestBody FederatedQueryRequest request) {
        log.info("Federated sync query: sql={} database={}", abbreviate(request.getSql()), request.getDatabase());
        return ResponseEntity.ok(service.executeSync(request));
    }

    /**
     * 健康检查。
     *
     * <p>GET /api/v1/federated/health
     */
    @Operation(summary = "联邦健康检查")
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "federated-query",
                "version", "0.1.0",
                "timestamp", Instant.now().toString()));
    }

    /**
     * 列出已知集群。
     *
     * <p>GET /api/v1/federated/clusters
     */
    @Operation(summary = "列出已知集群")
    @GetMapping("/clusters")
    public ResponseEntity<Map<String, Object>> clusters() {
        List<Map<String, Object>> list = service.listClusters();
        return ResponseEntity.ok(Map.of(
                "data", list,
                "total", list.size()));
    }

    /**
     * 列出降级告警事件。
     *
     * <p>GET /api/v1/federated/degradations
     */
    @Operation(summary = "列出降级告警事件")
    @GetMapping("/degradations")
    public ResponseEntity<Map<String, Object>> degradations(@RequestParam(defaultValue = "100") int limit) {
        List<DegradationAlert> alerts = service.listDegradeAlerts(limit);
        return ResponseEntity.ok(Map.of(
                "data", alerts,
                "total", alerts.size()));
    }

    private String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}