package com.levango7.dataenginebdp.streambatch.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.streambatch.dag.StreamBatchDagOrchestrator;
import com.levango7.dataenginebdp.streambatch.model.DagExecutionResult;
import com.levango7.dataenginebdp.streambatch.model.ExecutionStatus;
import com.levango7.dataenginebdp.streambatch.model.StreamBatchDag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * DAG 运行历史服务（任务运维中心核心）。
 *
 * <p>职责：
 * <ul>
 *   <li>DAG 执行完成时落库（被 StreamBatchOrchestrationService 调用）</li>
 *   <li>运行历史分页查询</li>
 *   <li>失败重跑（按 runId 复原参数重新执行）</li>
 *   <li>补数据（按时间区间生成 BACKFILL 实例）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DagRunService {

    private final DagRunRepository dagRunRepository;
    private final StreamBatchDagOrchestrator orchestrator;
    // 忽略未知属性：DagNode 等模型存在 Lombok 派生方法（如 isBatchNode），序列化后反序列化会报 UnrecognizedProperty
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 落库：DAG 执行完成后写入历史。
     *
     * @param dag       执行的 DAG
     * @param result    执行结果
     * @param runType   运行类型
     * @param triggeredBy 触发来源
     * @param sourceRunId 重跑来源（runType=RERUN 时有值）
     * @param bizTime   业务时间（BACKFILL 实例有值）
     * @return 持久化的运行记录
     */
    @Transactional
    public DagRunEntity recordRun(StreamBatchDag dag, DagExecutionResult result,
                                  DagRunType runType, String triggeredBy,
                                  Long sourceRunId, Instant bizTime) {
        try {
            DagRunEntity entity = DagRunEntity.builder()
                    .dagId(dag.getDagId())
                    .dagSnapshot(objectMapper.writeValueAsString(dag))
                    .runType(runType)
                    .status(result.getStatus())
                    .bizTime(bizTime)
                    .triggeredBy(triggeredBy)
                    .sourceRunId(sourceRunId)
                    .startTime(result.getStartTime())
                    .endTime(result.getEndTime())
                    .durationMs(result.getTotalDurationMs())
                    .nodeResultsJson(objectMapper.writeValueAsString(result.getNodeResults()))
                    .errorMessage(result.isSuccess() ? null : extractError(result))
                    .createdAt(Instant.now())
                    .build();
            DagRunEntity saved = dagRunRepository.save(entity);
            log.info("DAG 运行历史已落库: dagId={}, runId={}, status={}, runType={}",
                    dag.getDagId(), saved.getId(), saved.getStatus(), saved.getRunType());
            return saved;
        } catch (JsonProcessingException e) {
            log.error("DAG 运行历史序列化失败: dagId={}", dag.getDagId(), e);
            throw new IllegalStateException("无法序列化 DAG 运行历史", e);
        }
    }

    /**
     * 分页查询某 DAG 的运行历史。
     *
     * @param dagId  DAG ID
     * @param status 状态过滤（可空）
     * @param page   页号（0 起）
     * @param size   每页大小
     * @return 分页结果
     */
    @Transactional(readOnly = true)
    public Page<DagRunEntity> listRuns(String dagId, ExecutionStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        if (status != null) {
            return dagRunRepository.findByDagIdAndStatusOrderByStartTimeDesc(dagId, status, pageable);
        }
        return dagRunRepository.findByDagIdOrderByStartTimeDesc(dagId, pageable);
    }

    /**
     * 按 runId 重跑：复原原 DAG 参数重新执行。
     *
     * @param dagId       DAG ID
     * @param sourceRunId 历史 runId
     * @param triggeredBy 触发人
     * @return 新的执行结果
     */
    @Transactional
    public DagExecutionResult rerun(String dagId, Long sourceRunId, String triggeredBy) {
        DagRunEntity source = dagRunRepository.findById(sourceRunId)
                .filter(r -> r.getDagId().equals(dagId))
                .orElseThrow(() -> new IllegalArgumentException(
                        "runId=" + sourceRunId + " 不属于 dagId=" + dagId));

        try {
            StreamBatchDag dag = objectMapper.readValue(source.getDagSnapshot(), StreamBatchDag.class);
            log.info("重跑 DAG: dagId={}, sourceRunId={}, triggeredBy={}", dagId, sourceRunId, triggeredBy);

            DagExecutionResult result = orchestrator.orchestrate(dag);
            recordRun(dag, result, DagRunType.RERUN, triggeredBy, sourceRunId, source.getBizTime());
            return result;
        } catch (JsonProcessingException e) {
            log.error("重跑时反序列化 DAG 失败: runId={}", sourceRunId, e);
            throw new IllegalStateException("无法复原 DAG 参数进行重跑", e);
        }
    }

    /**
     * 补数据：按时间区间生成 BACKFILL 实例。
     *
     * @param dagId       DAG ID
     * @param startDate   开始日期（含）
     * @param endDate     结束日期（含）
     * @param intervalDays 间隔天数（1=每日）
     * @param triggeredBy 触发人
     * @return 生成的实例数
     */
    @Transactional
    public int backfill(String dagId, LocalDate startDate, LocalDate endDate,
                        int intervalDays, String triggeredBy) {
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate 不能晚于 endDate");
        }
        if (intervalDays < 1) {
            throw new IllegalArgumentException("intervalDays 必须 ≥ 1");
        }

        DagRunEntity latest = dagRunRepository.findByDagIdOrderByStartTimeDesc(
                        dagId, PageRequest.of(0, 1))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "dagId=" + dagId + " 无历史运行记录，无法补数据"));

        int created = 0;
        List<DagRunEntity> generated = new ArrayList<>();
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            try {
                StreamBatchDag dag = objectMapper.readValue(latest.getDagSnapshot(), StreamBatchDag.class);
                Instant bizTime = cursor.atStartOfDay().toInstant(ZoneOffset.UTC);
                dag.getDagConfig().put("backfill.bizDate", cursor.toString());
                dag.getDagConfig().put("backfill.source", triggeredBy);

                DagExecutionResult result = orchestrator.orchestrate(dag);
                DagRunEntity saved = recordRun(dag, result, DagRunType.BACKFILL,
                        triggeredBy, null, bizTime);
                generated.add(saved);
                created++;
            } catch (JsonProcessingException e) {
                log.error("补数据反序列化 DAG 失败: dagId={}, bizDate={}", dagId, cursor, e);
                throw new IllegalStateException("补数据实例生成失败", e);
            }
            cursor = cursor.plus(intervalDays, ChronoUnit.DAYS);
        }
        log.info("补数据完成: dagId={}, 生成 {} 个实例, 区间 [{} ~ {}]", dagId, created, startDate, endDate);
        return created;
    }

    /**
     * 提取第一个失败节点的错误信息。
     */
    private String extractError(DagExecutionResult result) {
        return result.getNodeResults().stream()
                .filter(n -> !n.isSuccess())
                .findFirst()
                .map(n -> n.getNodeId() + ": " + n.getErrorMessage())
                .orElse("DAG 执行失败，详见节点结果");
    }
}
