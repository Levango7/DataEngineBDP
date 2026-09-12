package com.levango7.dataenginebdp.streambatch.job;

import com.levango7.dataenginebdp.streambatch.model.DagExecutionResult;
import com.levango7.dataenginebdp.streambatch.model.DagNode;
import com.levango7.dataenginebdp.streambatch.model.StreamBatchDag;
import com.levango7.dataenginebdp.streambatch.model.TaskType;
import com.levango7.dataenginebdp.streambatch.service.StreamBatchOrchestrationService;
import com.levango7.dataenginebdp.common.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 作业服务：作业元数据 CRUD + 运行（转 DAG 提交）。
 *
 * <p>作业 → DAG 映射：单作业转为单节点 DAG（节点 type=作业类型，
 * config 存于节点 config），提交到 {@link StreamBatchOrchestrationService}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepository;
    private final StreamBatchOrchestrationService orchestrationService;

    /** 创建作业（自动注入当前租户）。 */
    @Transactional
    public JobEntity create(JobEntity job) {
        String tenantId = requireTenant();
        job.setTenantId(tenantId);
        job.setStatus("draft");
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        JobEntity saved = jobRepository.save(job);
        log.info("创建作业: id={}, name={}, type={}, workspace={}, tenant={}",
                saved.getId(), saved.getName(), saved.getType(), saved.getWorkspaceId(), tenantId);
        return saved;
    }

    /** 分页列表（workspace 过滤 + 租户隔离）。 */
    public Page<JobEntity> list(String workspaceId, int page, int size) {
        String tenantId = requireTenant();
        PageRequest pr = PageRequest.of(Math.max(page - 1, 0), size);
        if (workspaceId == null || workspaceId.isBlank()) {
            return jobRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pr);
        }
        return jobRepository.findByTenantIdAndWorkspaceIdOrderByCreatedAtDesc(tenantId, workspaceId, pr);
    }

    /** 详情（租户隔离）。 */
    public Optional<JobEntity> get(Long id) {
        String tenantId = requireTenant();
        return jobRepository.findByTenantIdAndId(tenantId, id);
    }

    /** 更新作业（名称/类型/配置/调度，租户隔离）。 */
    @Transactional
    public Optional<JobEntity> update(Long id, JobEntity patch) {
        String tenantId = requireTenant();
        return jobRepository.findByTenantIdAndId(tenantId, id).map(job -> {
            if (patch.getName() != null) {
                job.setName(patch.getName());
            }
            if (patch.getType() != null) {
                job.setType(patch.getType());
            }
            if (patch.getConfig() != null) {
                job.setConfig(patch.getConfig());
            }
            if (patch.getSchedule() != null) {
                job.setSchedule(patch.getSchedule());
            }
            if (patch.getOwner() != null) {
                job.setOwner(patch.getOwner());
            }
            job.setUpdatedAt(Instant.now());
            return jobRepository.save(job);
        });
    }

    /** 删除作业（租户隔离）。 */
    @Transactional
    public boolean delete(Long id) {
        String tenantId = requireTenant();
        if (!jobRepository.existsByTenantIdAndId(tenantId, id)) {
            return false;
        }
        jobRepository.deleteById(id);
        log.info("删除作业: id={}, tenant={}", id, tenantId);
        return true;
    }

    /**
     * 运行作业：转换为单节点 DAG 提交（租户隔离）。
     *
     * @return 提交结果（含 dagId/status）
     */
    @Transactional
    public Optional<DagExecutionResult> run(Long id) {
        String tenantId = requireTenant();
        return jobRepository.findByTenantIdAndId(tenantId, id).map(job -> {
            StreamBatchDag dag = buildDagFromJob(job);
            DagExecutionResult result = orchestrationService.submitDag(dag);
            job.setStatus("active");
            if (result.getStatus() != null) {
                job.setLastRunStatus(result.getStatus().name());
            }
            job.setUpdatedAt(Instant.now());
            jobRepository.save(job);
            log.info("作业运行: jobId={}, dagId={}, status={}, tenant={}",
                    job.getId(), result.getDagId(), result.getStatus(), tenantId);
            return result;
        });
    }

    /**
     * 取消/暂停作业（租户隔离；真实作业取消需 Flink/Spark 集群，
     * 见 ROADMAP「真实提交已实现，需真实集群验证」）。
     */
    @Transactional
    public boolean cancel(Long id) {
        String tenantId = requireTenant();
        return jobRepository.findByTenantIdAndId(tenantId, id).map(job -> {
            job.setStatus("paused");
            job.setLastRunStatus("CANCELLED");
            job.setUpdatedAt(Instant.now());
            jobRepository.save(job);
            log.info("作业已暂停: id={}, tenant={}", id, tenantId);
            return true;
        }).orElse(false);
    }

    /**
     * 从 TenantContext 获取当前租户 ID；缺失时抛 403 语义异常。
     *
     * @return 当前租户 ID
     * @throws IllegalStateException 当上下文无租户时
     */
    private static String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new com.levango7.dataenginebdp.streambatch.job.TenantForbiddenException(
                    "缺少租户上下文，拒绝访问作业资源");
        }
        return tenantId;
    }

    /** 作业 → 单节点 DAG。 */
    private StreamBatchDag buildDagFromJob(JobEntity job) {
        StreamBatchDag dag = new StreamBatchDag();
        dag.setDagId(JobService.jobIdToDagId(job.getId()));
        dag.setName("job-" + job.getName());
        dag.setDescription("作业运行（jobId=" + job.getId() + "）");
        DagNode node = DagNode.builder()
                .nodeId("job-" + job.getId() + "-node")
                .name(job.getName())
                .taskType(mapTaskType(job.getType()))
                .build();
        dag.setNodes(List.of(node));
        return dag;
    }

    /** 作业类型 → TaskType（前端类型名 → 引擎枚举，未知回退 UNIFIED）。 */
    private TaskType mapTaskType(String jobType) {
        if (jobType == null) {
            return TaskType.UNIFIED_STREAM_BATCH;
        }
        switch (jobType.toLowerCase()) {
            case "spark":
            case "spark_batch":
            case "batch":
                return TaskType.SPARK_BATCH;
            case "flink":
            case "flink_stream":
            case "stream":
                return TaskType.FLINK_STREAM;
            default:
                return TaskType.UNIFIED_STREAM_BATCH;
        }
    }

    /** jobId → dagId（稳定映射）。 */
    public static String jobIdToDagId(Long jobId) {
        return "job-" + jobId;
    }
}
