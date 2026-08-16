package com.levango7.dataenginebdp.streambatch.job;

import com.levango7.dataenginebdp.streambatch.run.DagRunEntity;
import com.levango7.dataenginebdp.streambatch.run.DagRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 作业日志服务：从 DAG 运行历史与节点结果中聚合作业执行日志。
 *
 * <p>日志来源：
 * <ul>
 *   <li>DAG 运行历史（{@link DagRunEntity}）的 nodeResultsJson、errorMessage</li>
 *   <li>作业元数据（{@link JobEntity}）的 status、lastRunStatus</li>
 * </ul>
 * 真实生产环境可扩展为从 Loki/ES 拉取实时日志。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobLogService {

    private final JobRepository jobRepository;
    private final DagRunRepository dagRunRepository;

    /**
     * 查询作业执行日志。
     *
     * @param jobId 作业 ID
     * @return 日志文本（多行）
     */
    public String getJobLogs(Long jobId) {
        JobEntity job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return "# 作业 " + jobId + " 不存在\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# 作业日志: id=").append(jobId).append(", name=").append(job.getName()).append('\n');
        sb.append("# 类型: ").append(job.getType()).append(", 状态: ").append(job.getStatus()).append('\n');
        if (job.getLastRunStatus() != null) {
            sb.append("# 最近运行状态: ").append(job.getLastRunStatus()).append('\n');
        }
        sb.append("# 创建时间: ").append(job.getCreatedAt()).append('\n');
        if (job.getUpdatedAt() != null) {
            sb.append("# 更新时间: ").append(job.getUpdatedAt()).append('\n');
        }
        sb.append('\n');

        // 查询关联的 DAG 运行历史（dagId = job-<jobId>）
        String dagId = JobService.jobIdToDagId(jobId);
        Page<DagRunEntity> runs = dagRunRepository.findByDagIdOrderByStartTimeDesc(
                dagId, PageRequest.of(0, 10));
        List<DagRunEntity> runList = runs.getContent();

        if (runList.isEmpty()) {
            sb.append("# 暂无 DAG 运行历史（作业可能尚未运行过）\n");
            return sb.toString();
        }

        sb.append("# ===== DAG 运行历史（最近 ").append(runList.size()).append(" 次） =====\n\n");
        for (DagRunEntity run : runList) {
            sb.append("## Run #").append(run.getId())
                    .append("  status=").append(run.getStatus())
                    .append("  type=").append(run.getRunType());
            if (run.getStartTime() != null) {
                sb.append("  start=").append(run.getStartTime());
            }
            if (run.getDurationMs() != null) {
                sb.append("  duration=").append(run.getDurationMs()).append("ms");
            }
            sb.append('\n');

            // 节点结果
            if (run.getNodeResultsJson() != null && !run.getNodeResultsJson().isBlank()) {
                sb.append("[节点结果]\n").append(run.getNodeResultsJson()).append('\n');
            }
            // 错误信息
            if (run.getErrorMessage() != null && !run.getErrorMessage().isBlank()) {
                sb.append("[错误信息]\n").append(run.getErrorMessage()).append('\n');
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    /**
     * 查询作业当前状态（含进度）。
     *
     * @param jobId 作业 ID
     * @return 状态视图 Map；作业不存在时返回 null
     */
    public java.util.Map<String, Object> getJobStatus(Long jobId) {
        JobEntity job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return null;
        }
        java.util.Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("status", job.getStatus() == null ? "unknown" : job.getStatus());
        status.put("lastRunStatus", job.getLastRunStatus());
        // 进度：active=50, paused=0, draft=0, 其他=100
        int progress;
        switch (job.getStatus() == null ? "" : job.getStatus()) {
            case "active":
                progress = 50;
                break;
            case "paused":
            case "draft":
                progress = 0;
                break;
            default:
                progress = 100;
        }
        status.put("progress", progress);
        status.put("updatedAt", job.getUpdatedAt() == null ? null : job.getUpdatedAt().toString());
        return status;
    }
}