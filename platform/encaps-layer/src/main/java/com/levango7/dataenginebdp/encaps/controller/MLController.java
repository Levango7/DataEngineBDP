package com.levango7.dataenginebdp.encaps.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.encaps.model.InferenceServiceEntity;
import com.levango7.dataenginebdp.encaps.model.MlModelEntity;
import com.levango7.dataenginebdp.encaps.repository.InferenceServiceRepository;
import com.levango7.dataenginebdp.encaps.repository.MlModelRepository;
import com.levango7.dataenginebdp.common.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 机器学习端点（ROADMAP 前后端接线：前端 /ml/models、/ml/inference-services）。
 *
 * <p>统一前缀：{@code /api/v1/ml}</p>
 * <ul>
 *   <li>GET    /models                          — 列出模型仓库（按 name 聚合，取最新版本）</li>
 *   <li>GET    /models/{id}                     — 获取模型详情</li>
 *   <li>POST   /models                          — 注册模型（新版本）</li>
 *   <li>DELETE /models/{id}                     — 删除模型某版本</li>
 *   <li>GET    /models/{name}/versions          — 列出模型全部版本</li>
 *   <li>GET    /inference-services              — 列出推理服务</li>
 *   <li>POST   /inference-services              — 部署推理服务</li>
 *   <li>DELETE /inference-services/{id}         — 停止/删除推理服务</li>
 *   <li>POST   /inference-services/{id}/scale   — 扩缩容推理服务</li>
 * </ul>
 *
 * <p>所有端点强制 {@link TenantContext} 租户隔离；返回体对齐前端
 * {@code dev-ml.ts} 中的 MlModel/ModelVersion/InferenceService 契约。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ml")
public class MLController {

    private final MlModelRepository modelRepository;
    private final InferenceServiceRepository inferenceRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /* ============================ 请求体契约 ============================ */

    /** 模型注册请求（对齐前端 ModelRegisterRequest）。 */
    public record ModelRegisterRequest(
            @NotBlank String name,
            @NotBlank String algorithm,
            String trainJobId,
            String modelPath,
            String version,
            JsonNode metrics,
            String description) {
    }

    /** 推理服务部署请求（对齐前端 InferenceDeployRequest）。 */
    public record InferenceDeployRequest(
            String serviceName,
            @NotBlank String modelName,
            @NotBlank String version,
            Integer replicas,
            String resourceSpec) {
    }

    /** 推理服务扩缩容请求（对齐前端 InferenceScaleRequest）。 */
    public record InferenceScaleRequest(Integer replicas) {
    }

    /* ============================ 模型仓库端点 ============================ */

    /** 列出模型仓库（按 name 聚合，取最新版本作为 latestVersion）。 */
    @GetMapping("/models")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> listModels(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String algorithm) {
        String tenantId = requireTenant();
        List<MlModelEntity> all = queryModels(tenantId, keyword, algorithm);
        // 按 name 聚合，每组取最新版本（registeredAt 倒序首条）
        Map<String, MlModelEntity> latestByName = all.stream()
                .collect(Collectors.toMap(
                        MlModelEntity::getName,
                        e -> e,
                        (a, b) -> a.getRegisteredAt() == null ? b
                                : b.getRegisteredAt() == null ? a
                                : a.getRegisteredAt().isAfter(b.getRegisteredAt()) ? a : b,
                        LinkedHashMap::new));
        List<Map<String, Object>> result = latestByName.values().stream()
                .map(this::toModelView)
                .toList();
        return ResponseEntity.ok(result);
    }

    /** 获取模型详情（按 id 取某版本）。 */
    @GetMapping("/models/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getModel(@PathVariable Long id) {
        String tenantId = requireTenant();
        return modelRepository.findByIdAndTenantId(id, tenantId)
                .map(m -> ResponseEntity.ok((Object) toModelView(m)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 注册模型（新版本）。 */
    @PostMapping("/models")
    @Transactional
    public ResponseEntity<Map<String, Object>> registerModel(
            @Valid @RequestBody ModelRegisterRequest req) {
        String tenantId = requireTenant();
        String version = resolveVersion(req, tenantId);
        MlModelEntity entity = MlModelEntity.builder()
                .name(req.name())
                .algorithm(req.algorithm())
                .version(version)
                .status("REGISTERED")
                .metricsJson(req.metrics() == null ? null : req.metrics().toString())
                .trainJobId(req.trainJobId())
                .modelPath(req.modelPath())
                .description(req.description())
                .tenantId(tenantId)
                .registeredAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        MlModelEntity saved = modelRepository.save(entity);
        log.info("注册模型: id={}, name={}, version={}, algorithm={}, tenant={}",
                saved.getId(), saved.getName(), saved.getVersion(), saved.getAlgorithm(), tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(toModelView(saved));
    }

    /** 删除模型某版本。 */
    @DeleteMapping("/models/{id}")
    @Transactional
    public ResponseEntity<Void> deleteModel(@PathVariable Long id) {
        String tenantId = requireTenant();
        Optional<MlModelEntity> entity = modelRepository.findByIdAndTenantId(id, tenantId);
        if (entity.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        modelRepository.delete(entity.get());
        log.info("删除模型版本: id={}, name={}, tenant={}", id, entity.get().getName(), tenantId);
        return ResponseEntity.noContent().build();
    }

    /** 列出模型全部版本（对齐前端 listModelVersions）。 */
    @GetMapping("/models/{name}/versions")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> listModelVersions(
            @PathVariable String name) {
        String tenantId = requireTenant();
        List<MlModelEntity> versions = modelRepository
                .findByTenantIdAndNameOrderByRegisteredAtDesc(tenantId, name);
        List<Map<String, Object>> result = versions.stream()
                .map(this::toVersionView)
                .toList();
        return ResponseEntity.ok(result);
    }

    /* ============================ 推理服务端点 ============================ */

    /** 列出推理服务。 */
    @GetMapping("/inference-services")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> listInferenceServices(
            @RequestParam(required = false) String status) {
        String tenantId = requireTenant();
        List<InferenceServiceEntity> all = (status == null || status.isBlank())
                ? inferenceRepository.findByTenantIdOrderByDeployedAtDesc(tenantId)
                : inferenceRepository.findByTenantIdAndStatusOrderByDeployedAtDesc(tenantId, status);
        List<Map<String, Object>> result = all.stream()
                .map(this::toInferenceView)
                .toList();
        return ResponseEntity.ok(result);
    }

    /** 部署推理服务。 */
    @PostMapping("/inference-services")
    @Transactional
    public ResponseEntity<Map<String, Object>> deployInference(
            @Valid @RequestBody InferenceDeployRequest req) {
        String tenantId = requireTenant();
        int replicas = req.replicas() != null && req.replicas() > 0 ? req.replicas() : 1;
        String serviceName = req.serviceName() != null && !req.serviceName().isBlank()
                ? req.serviceName()
                : req.modelName() + "-" + req.version();
        InferenceServiceEntity entity = InferenceServiceEntity.builder()
                .serviceName(serviceName)
                .modelName(req.modelName())
                .modelVersion(req.version())
                .status("RUNNING")
                .replicas(replicas)
                .desiredReplicas(replicas)
                .qps(0.0)
                .latencyMs(0.0)
                .endpoint("/ml/inference/" + serviceName)
                .resourceSpec(req.resourceSpec())
                .tenantId(tenantId)
                .deployedAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        InferenceServiceEntity saved = inferenceRepository.save(entity);
        log.info("部署推理服务: id={}, serviceName={}, model={}:{}, replicas={}, tenant={}",
                saved.getId(), saved.getServiceName(), saved.getModelName(),
                saved.getModelVersion(), replicas, tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(toInferenceView(saved));
    }

    /** 停止/删除推理服务。 */
    @DeleteMapping("/inference-services/{id}")
    @Transactional
    public ResponseEntity<Void> stopInference(@PathVariable Long id) {
        String tenantId = requireTenant();
        Optional<InferenceServiceEntity> entity = inferenceRepository.findByIdAndTenantId(id, tenantId);
        if (entity.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        inferenceRepository.delete(entity.get());
        log.info("停止推理服务: id={}, serviceName={}, tenant={}",
                id, entity.get().getServiceName(), tenantId);
        return ResponseEntity.noContent().build();
    }

    /** 扩缩容推理服务（对齐前端 scaleInference）。 */
    @PostMapping("/inference-services/{id}/scale")
    @Transactional
    public ResponseEntity<?> scaleInference(
            @PathVariable Long id,
            @Valid @RequestBody InferenceScaleRequest req) {
        String tenantId = requireTenant();
        if (req.replicas() == null || req.replicas() < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "replicas 必须为非负整数"));
        }
        return inferenceRepository.findByIdAndTenantId(id, tenantId).map(entity -> {
            entity.setDesiredReplicas(req.replicas());
            entity.setStatus("SCALING");
            entity.setUpdatedAt(Instant.now());
            InferenceServiceEntity saved = inferenceRepository.save(entity);
            log.info("扩缩容推理服务: id={}, serviceName={}, desiredReplicas={}, tenant={}",
                    id, saved.getServiceName(), req.replicas(), tenantId);
            return ResponseEntity.ok((Object) toInferenceView(saved));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /* ============================ 私有辅助 ============================ */

    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    /** 按关键字 + 算法组合查询模型。 */
    private List<MlModelEntity> queryModels(String tenantId, String keyword, String algorithm) {
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        boolean hasAlgorithm = algorithm != null && !algorithm.isBlank();
        if (hasKeyword && hasAlgorithm) {
            return modelRepository
                    .findByTenantIdAndAlgorithmAndNameContainingIgnoreCaseOrderByRegisteredAtDesc(
                            tenantId, algorithm, keyword);
        }
        if (hasAlgorithm) {
            return modelRepository.findByTenantIdAndAlgorithmOrderByRegisteredAtDesc(
                    tenantId, algorithm);
        }
        if (hasKeyword) {
            return modelRepository
                    .findByTenantIdAndNameContainingIgnoreCaseOrderByRegisteredAtDesc(
                            tenantId, keyword);
        }
        return modelRepository.findByTenantIdOrderByRegisteredAtDesc(tenantId);
    }

    /** 版本号解析：请求未指定时按同 name 已有版本数自增生成 v{n+1}。 */
    private String resolveVersion(ModelRegisterRequest req, String tenantId) {
        if (req.version() != null && !req.version().isBlank()) {
            return req.version();
        }
        long count = modelRepository
                .findByTenantIdAndNameOrderByRegisteredAtDesc(tenantId, req.name())
                .size();
        return "v" + (count + 1);
    }

    /** 模型视图（对齐前端 MlModel）。 */
    private Map<String, Object> toModelView(MlModelEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(e.getId()));
        m.put("name", e.getName());
        m.put("algorithm", e.getAlgorithm());
        m.put("latestVersion", e.getVersion());
        m.put("status", e.getStatus());
        m.put("metrics", parseMetrics(e.getMetricsJson()));
        m.put("trainJobId", e.getTrainJobId());
        m.put("modelPath", e.getModelPath());
        m.put("description", e.getDescription());
        m.put("registeredAt", e.getRegisteredAt() == null ? null : e.getRegisteredAt().toString());
        m.put("updatedAt", e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
        return m;
    }

    /** 模型版本视图（对齐前端 ModelVersion）。 */
    private Map<String, Object> toVersionView(MlModelEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("modelName", e.getName());
        m.put("version", e.getVersion());
        m.put("status", e.getStatus());
        m.put("metrics", parseMetrics(e.getMetricsJson()));
        m.put("trainJobId", e.getTrainJobId());
        m.put("modelPath", e.getModelPath());
        m.put("registeredAt", e.getRegisteredAt() == null ? null : e.getRegisteredAt().toString());
        m.put("description", e.getDescription());
        return m;
    }

    /** 推理服务视图（对齐前端 InferenceService）。 */
    private Map<String, Object> toInferenceView(InferenceServiceEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(e.getId()));
        m.put("serviceName", e.getServiceName());
        m.put("modelName", e.getModelName());
        m.put("modelVersion", e.getModelVersion());
        m.put("status", e.getStatus());
        m.put("replicas", e.getReplicas());
        m.put("desiredReplicas", e.getDesiredReplicas());
        m.put("qps", e.getQps());
        m.put("latencyMs", e.getLatencyMs());
        m.put("endpoint", e.getEndpoint());
        m.put("deployedAt", e.getDeployedAt() == null ? null : e.getDeployedAt().toString());
        return m;
    }

    /** 解析 metrics JSON 为 Map；非法 JSON 返回空 Map。 */
    private Map<String, Number> parseMetrics(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            Map<String, Number> metrics = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> {
                JsonNode v = entry.getValue();
                if (v.isNumber()) {
                    metrics.put(entry.getKey(), v.numberValue());
                }
            });
            return metrics;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

}