package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
 *   <li>GET  /models       — 模型列表</li>
 *   <li>GET  /eval-metrics — 评估指标</li>
 *   <li>POST /finetune     — 微调</li>
 *   <li>POST /human-eval   — 人工评估</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/llmops")
public class LLMOpsController {

    /** 模型列表。 */
    @GetMapping("/models")
    public ResponseEntity<List<Map<String, Object>>> listModels() {
        // TODO: 接入 ml-platform / model-finetuning 真实数据
        log.info("列出 LLM 模型: tenant={}", TenantContext.getTenantId());
        return ResponseEntity.ok(List.of());
    }

    /** 评估指标。 */
    @GetMapping("/eval-metrics")
    public ResponseEntity<List<Map<String, Object>>> getEvalMetrics(
            @RequestParam(required = false) String modelName) {
        // TODO: 接入评估指标存储
        log.info("获取评估指标: model={}, tenant={}", modelName, TenantContext.getTenantId());
        return ResponseEntity.ok(List.of());
    }

    /** 微调请求体（对齐前端 FinetuneParams）。 */
    public record FinetuneRequest(
            String modelName,
            String baseModel,
            String trainingData,
            String gpuConfig,
            Integer epochs) {
    }

    /** 提交微调任务。 */
    @PostMapping("/finetune")
    public ResponseEntity<Map<String, Object>> submitFinetune(@RequestBody FinetuneRequest req) {
        // TODO: 转交 model-finetuning 服务
        log.info("提交微调: model={}, base={}, tenant={}",
                req.modelName(), req.baseModel(), TenantContext.getTenantId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", "ft-" + System.currentTimeMillis());
        result.put("status", "submitted");
        return ResponseEntity.ok(result);
    }

    /** 人工评估请求体。 */
    public record HumanEvalRequest(String modelName) {
    }

    /** 发起人工评估。 */
    @PostMapping("/human-eval")
    public ResponseEntity<Void> triggerHumanEval(@RequestBody HumanEvalRequest req) {
        // TODO: 创建人工评估任务
        log.info("发起人工评估: model={}, tenant={}", req.modelName(), TenantContext.getTenantId());
        return ResponseEntity.ok().build();
    }
}