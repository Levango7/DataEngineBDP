package com.levango7.dataenginebdp.encaps.service;

import com.levango7.dataenginebdp.encaps.model.EvalMetricEntity;
import com.levango7.dataenginebdp.encaps.model.FinetuneTaskEntity;
import com.levango7.dataenginebdp.encaps.model.InferenceServiceEntity;
import com.levango7.dataenginebdp.encaps.model.MlModelEntity;
import com.levango7.dataenginebdp.encaps.repository.EvalMetricRepository;
import com.levango7.dataenginebdp.encaps.repository.FinetuneTaskRepository;
import com.levango7.dataenginebdp.encaps.repository.InferenceServiceRepository;
import com.levango7.dataenginebdp.encaps.repository.MlModelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * LLMOps 业务逻辑服务。
 *
 * <p>封装大模型注册、微调、评估、推理服务管理的核心业务逻辑，
 * 供 {@code LLMOpsController} 调用，统一租户隔离与事务管理。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMOpsService {

    private final MlModelRepository modelRepository;
    private final FinetuneTaskRepository finetuneTaskRepository;
    private final EvalMetricRepository evalMetricRepository;
    private final InferenceServiceRepository inferenceServiceRepository;

    /* ============================ 模型管理 ============================ */

    /** 列出租户下全部 LLM 模型（algorithm=huggingface 视为大模型）。 */
    @Transactional(readOnly = true)
    public List<MlModelEntity> listModels(String tenantId) {
        return modelRepository.findByTenantIdOrderByRegisteredAtDesc(tenantId);
    }

    /** 按模型名过滤。 */
    @Transactional(readOnly = true)
    public List<MlModelEntity> listModelsByName(String tenantId, String modelName) {
        return modelRepository.findByTenantIdAndNameOrderByRegisteredAtDesc(tenantId, modelName);
    }

    /** 注册新模型。 */
    @Transactional
    public MlModelEntity registerModel(MlModelEntity entity) {
        entity.setRegisteredAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        if (entity.getStatus() == null) {
            entity.setStatus("REGISTERED");
        }
        MlModelEntity saved = modelRepository.save(entity);
        log.info("注册 LLM 模型: id={}, name={}, version={}, tenant={}",
                saved.getId(), saved.getName(), saved.getVersion(), saved.getTenantId());
        return saved;
    }

    /* ============================ 微调任务 ============================ */

    /** 列出租户下全部微调任务。 */
    @Transactional(readOnly = true)
    public List<FinetuneTaskEntity> listFinetuneTasks(String tenantId) {
        return finetuneTaskRepository.findByTenantIdOrderBySubmittedAtDesc(tenantId);
    }

    /** 提交微调任务（生成业务 taskId，初始状态 SUBMITTED）。 */
    @Transactional
    public FinetuneTaskEntity submitFinetune(FinetuneTaskEntity entity) {
        String taskId = "ft-" + System.currentTimeMillis();
        entity.setTaskId(taskId);
        entity.setStatus("SUBMITTED");
        entity.setProgress(0);
        entity.setSubmittedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        FinetuneTaskEntity saved = finetuneTaskRepository.save(entity);
        log.info("提交微调任务: taskId={}, model={}, base={}, tenant={}",
                saved.getTaskId(), saved.getModelName(), saved.getBaseModel(), saved.getTenantId());
        return saved;
    }

    /** 查询微调任务状态。 */
    @Transactional(readOnly = true)
    public Optional<FinetuneTaskEntity> getFinetuneTask(String tenantId, String taskId) {
        return finetuneTaskRepository.findByTaskIdAndTenantId(taskId, tenantId);
    }

    /**
     * 模拟推进微调任务进度。
     *
     * <p>真实场景下由训练引擎回调更新；此处为占位实现，
     * 将 RUNNING 状态的任务进度按比例推进，便于前端轮询演示。</p>
     */
    @Transactional
    public Optional<FinetuneTaskEntity> advanceFinetune(String tenantId, String taskId) {
        return finetuneTaskRepository.findByTaskIdAndTenantId(taskId, tenantId).map(entity -> {
            if (!"RUNNING".equals(entity.getStatus())) {
                return entity;
            }
            int current = entity.getProgress() == null ? 0 : entity.getProgress();
            int next = Math.min(100, current + 10);
            entity.setProgress(next);
            if (next >= 100) {
                entity.setStatus("SUCCEEDED");
                entity.setFinishedAt(Instant.now());
            }
            entity.setUpdatedAt(Instant.now());
            return finetuneTaskRepository.save(entity);
        });
    }

    /* ============================ 评估指标 ============================ */

    /** 列出租户下全部评估指标。 */
    @Transactional(readOnly = true)
    public List<EvalMetricEntity> listEvalMetrics(String tenantId) {
        return evalMetricRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /** 按模型名过滤评估指标。 */
    @Transactional(readOnly = true)
    public List<EvalMetricEntity> listEvalMetricsByModel(String tenantId, String modelName) {
        return evalMetricRepository.findByTenantIdAndModelNameOrderByCreatedAtDesc(
                tenantId, modelName);
    }

    /** 创建评估指标。 */
    @Transactional
    public EvalMetricEntity createEvalMetric(EvalMetricEntity entity) {
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        if (entity.getEvalType() == null) {
            entity.setEvalType("auto");
        }
        EvalMetricEntity saved = evalMetricRepository.save(entity);
        log.info("创建评估指标: id={}, model={}, type={}, tenant={}",
                saved.getId(), saved.getModelName(), saved.getEvalType(), saved.getTenantId());
        return saved;
    }

    /* ============================ 推理服务 ============================ */

    /** 列出租户下全部推理服务。 */
    @Transactional(readOnly = true)
    public List<InferenceServiceEntity> listInferenceServices(String tenantId) {
        return inferenceServiceRepository.findByTenantIdOrderByDeployedAtDesc(tenantId);
    }

    /** 按状态过滤推理服务。 */
    @Transactional(readOnly = true)
    public List<InferenceServiceEntity> listInferenceServicesByStatus(
            String tenantId, String status) {
        return inferenceServiceRepository.findByTenantIdAndStatusOrderByDeployedAtDesc(
                tenantId, status);
    }
}