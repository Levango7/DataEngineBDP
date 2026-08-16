package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.model.EvalMetricEntity;
import com.levango7.dataenginebdp.encaps.model.FinetuneTaskEntity;
import com.levango7.dataenginebdp.encaps.model.InferenceServiceEntity;
import com.levango7.dataenginebdp.encaps.model.MlModelEntity;
import com.levango7.dataenginebdp.encaps.security.TenantContext;
import com.levango7.dataenginebdp.encaps.service.LLMOpsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLMOps 端点（ROADMAP 前后端接线：前端 /llmops）。
 *
 * <p>提供大模型注册、微调、评估、部署一体化运营能力。
 * 统一前缀：{@code /api/v1/llmops}</p>
 *
 * <ul>
 *   <li>GET    /models                       — 模型列表</li>
 *   <li>POST   /models                       — 注册新模型</li>
 *   <li>GET    /eval-metrics                 — 评估指标列表</li>
 *   <li>POST   /eval-metrics                 — 创建评估指标</li>
 *   <li>POST   /finetune                     — 提交微调任务</li>
 *   <li>GET    /finetune/{taskId}            — 查询微调任务状态</li>
 *   <li>POST   /human-eval                   — 人工评估</li>
 *   <li>GET    /inference-services           — 推理服务列表</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/llmops")
public class LLMOpsController {

    private final LLMOpsService llmOpsService;

    /* ============================ 请求体契约 ============================ */

    /** 模型注册请求（对齐前端 ModelRegisterParams）。 */
    public record ModelRegisterRequest(
            @NotBlank String name,
            @NotBlank String algorithm,
            String version,
            String trainJobId,
            String modelPath,
            String description) {
    }

    /** 微调请求体（对齐前端 FinetuneParams）。 */
    public record FinetuneRequest(
            String modelName,
            String baseModel,
            String trainingData,
            String gpuConfig,
            Integer epochs) {
    }

    /** 评估指标创建请求（对齐前端 EvalMetricCreateParams）。 */
    public record EvalMetricCreateRequest(
            @NotBlank String modelName,
            String modelVersion,
            String evalType,
            Double accuracy,
            Double hallucinationRate,
            Double baseLiftPt,
            String dataset) {
    }

    /** 人工评估请求体。 */
    public record HumanEvalRequest(String modelName) {
    }

    /* ============================ 模型管理端点 ============================ */

    /** 模型列表。 */
    @GetMapping("/models")
    public ResponseEntity<List<Map<String, Object>>> listModels(
            @RequestParam(required = false) String modelName) {
        String tenantId = requireTenant();
        List<MlModelEntity> models = (modelName == null || modelName.isBlank())
                ? llmOpsService.listModels(tenantId)
                : llmOpsService.listModelsByName(tenantId, modelName);
        return ResponseEntity.ok(models.stream().map(this::toModelView).toList());
    }

    /** 注册新模型。 */
    @PostMapping("/models")
    public ResponseEntity<Map<String, Object>> registerModel(
            @Valid @RequestBody ModelRegisterRequest req) {
        String tenantId = requireTenant();
        String version = req.version() != null && !req.version().isBlank()
                ? req.version()
                : "v1";
        MlModelEntity entity = MlModelEntity.builder()
                .name(req.name())
                .algorithm(req.algorithm())
                .version(version)
                .status("REGISTERED")
                .trainJobId(req.trainJobId())
                .modelPath(req.modelPath())
                .description(req.description())
                .tenantId(tenantId)
                .build();
        MlModelEntity saved = llmOpsService.registerModel(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(toModelView(saved));
    }

    /* ============================ 评估指标端点 ============================ */

    /** 评估指标列表。 */
    @GetMapping("/eval-metrics")
    public ResponseEntity<List<Map<String, Object>>> getEvalMetrics(
            @RequestParam(required = false) String modelName) {
        String tenantId = requireTenant();
        List<EvalMetricEntity> metrics = (modelName == null || modelName.isBlank())
                ? llmOpsService.listEvalMetrics(tenantId)
                : llmOpsService.listEvalMetricsByModel(tenantId, modelName);
        return ResponseEntity.ok(metrics.stream().map(this::toEvalMetricView).toList());
    }

    /** 创建评估指标。 */
    @PostMapping("/eval-metrics")
    public ResponseEntity<Map<String, Object>> createEvalMetric(
            @Valid @RequestBody EvalMetricCreateRequest req) {
        String tenantId = requireTenant();
        EvalMetricEntity entity = EvalMetricEntity.builder()
                .modelName(req.modelName())
                .modelVersion(req.modelVersion())
                .evalType(req.evalType() == null ? "auto" : req.evalType())
                .accuracy(req.accuracy())
                .hallucinationRate(req.hallucinationRate())
                .baseLiftPt(req.baseLiftPt())
                .dataset(req.dataset())
                .tenantId(tenantId)
                .build();
        EvalMetricEntity saved = llmOpsService.createEvalMetric(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(toEvalMetricView(saved));
    }

    /* ============================ 微调任务端点 ============================ */

    /** 提交微调任务。 */
    @PostMapping("/finetune")
    public ResponseEntity<Map<String, Object>> submitFinetune(@RequestBody FinetuneRequest req) {
        String tenantId = requireTenant();
        FinetuneTaskEntity entity = FinetuneTaskEntity.builder()
                .modelName(req.modelName())
                .baseModel(req.baseModel())
                .trainingData(req.trainingData())
                .gpuConfig(req.gpuConfig())
                .epochs(req.epochs())
                .tenantId(tenantId)
                .build();
        FinetuneTaskEntity saved = llmOpsService.submitFinetune(entity);
        return ResponseEntity.ok(toFinetuneView(saved));
    }

    /** 微调任务列表。 */
    @GetMapping("/finetune")
    public ResponseEntity<List<Map<String, Object>>> listFinetuneTasks() {
        String tenantId = requireTenant();
        return ResponseEntity.ok(llmOpsService.listFinetuneTasks(tenantId)
                .stream().map(this::toFinetuneView).toList());
    }

    /** 查询微调任务状态。 */
    @GetMapping("/finetune/{taskId}")
    public ResponseEntity<?> getFinetuneStatus(@PathVariable String taskId) {
        String tenantId = requireTenant();
        return llmOpsService.getFinetuneTask(tenantId, taskId)
                .map(t -> ResponseEntity.ok((Object) toFinetuneView(t)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /* ============================ 人工评估端点 ============================ */

    /** 发起人工评估。 */
    @PostMapping("/human-eval")
    public ResponseEntity<Map<String, Object>> triggerHumanEval(@RequestBody HumanEvalRequest req) {
        String tenantId = requireTenant();
        // 创建一条 evalType=human 的评估指标记录
        EvalMetricEntity entity = EvalMetricEntity.builder()
                .modelName(req.modelName())
                .evalType("human")
                .tenantId(tenantId)
                .build();
        EvalMetricEntity saved = llmOpsService.createEvalMetric(entity);
        log.info("发起人工评估: model={}, metricId={}, tenant={}",
                req.modelName(), saved.getId(), tenantId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metricId", String.valueOf(saved.getId()));
        result.put("modelName", saved.getModelName());
        result.put("status", "submitted");
        return ResponseEntity.ok(result);
    }

    /* ============================ 推理服务端点 ============================ */

    /** 推理服务列表。 */
    @GetMapping("/inference-services")
    public ResponseEntity<List<Map<String, Object>>> listInferenceServices(
            @RequestParam(required = false) String status) {
        String tenantId = requireTenant();
        List<InferenceServiceEntity> services = (status == null || status.isBlank())
                ? llmOpsService.listInferenceServices(tenantId)
                : llmOpsService.listInferenceServicesByStatus(tenantId, status);
        return ResponseEntity.ok(services.stream().map(this::toInferenceView).toList());
    }

    /* ============================ 私有辅助 ============================ */

    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    /** 模型视图（对齐前端 ModelRegistry）。 */
    private Map<String, Object> toModelView(MlModelEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(e.getId()));
        m.put("name", e.getName());
        m.put("algorithm", e.getAlgorithm());
        m.put("version", e.getVersion());
        m.put("status", e.getStatus());
        m.put("trainJobId", e.getTrainJobId());
        m.put("modelPath", e.getModelPath());
        m.put("description", e.getDescription());
        m.put("registeredAt", e.getRegisteredAt() == null ? null : e.getRegisteredAt().toString());
        m.put("updatedAt", e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
        return m;
    }

    /** 评估指标视图（对齐前端 EvalMetric）。 */
    private Map<String, Object> toEvalMetricView(EvalMetricEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(e.getId()));
        m.put("modelName", e.getModelName());
        m.put("modelVersion", e.getModelVersion());
        m.put("evalType", e.getEvalType());
        m.put("accuracy", e.getAccuracy());
        m.put("hallucinationRate", e.getHallucinationRate());
        m.put("baseLiftPt", e.getBaseLiftPt());
        m.put("dataset", e.getDataset());
        m.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        return m;
    }

    /** 微调任务视图（对齐前端 FinetuneResult）。 */
    private Map<String, Object> toFinetuneView(FinetuneTaskEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", e.getTaskId());
        m.put("modelName", e.getModelName());
        m.put("baseModel", e.getBaseModel());
        m.put("trainingData", e.getTrainingData());
        m.put("gpuConfig", e.getGpuConfig());
        m.put("epochs", e.getEpochs());
        m.put("status", e.getStatus());
        m.put("progress", e.getProgress());
        m.put("errorMessage", e.getErrorMessage());
        m.put("submittedAt", e.getSubmittedAt() == null ? null : e.getSubmittedAt().toString());
        m.put("startedAt", e.getStartedAt() == null ? null : e.getStartedAt().toString());
        m.put("finishedAt", e.getFinishedAt() == null ? null : e.getFinishedAt().toString());
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
        m.put("resourceSpec", e.getResourceSpec());
        m.put("deployedAt", e.getDeployedAt() == null ? null : e.getDeployedAt().toString());
        return m;
    }
}
