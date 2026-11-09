package com.levango7.dataenginebdp.streambatch.job;

import com.levango7.dataenginebdp.common.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 作业 cron 调度触发器（可售性补缺批 3，2026-11-09）。
 *
 * <p>背景：此前 {@code sb_job.schedule} 只在 CRUD 中落库与回显，从不被任何组件解析
 * （{@code DagRunType.SCHEDULED} 枚举存在但无生产者）——投标语义上的「定时离线任务」
 * 实际只能人工点击。本组件把 cron 真正接上：周期扫描带 schedule 的作业，到点以
 * {@link com.levango7.dataenginebdp.streambatch.run.DagRunType#SCHEDULED} 触发
 * {@link JobService#runScheduled(Long)}。</p>
 *
 * <p><b>参与调度的条件</b>：{@code schedule} 非空且作业状态为 {@code active}
 * （创建后为 {@code draft}；执行一次「运行」或调度触发后即为 {@code active}；
 * 「取消/暂停」置 {@code paused} 即停止调度——与既有 run/cancel 语义自洽）。</p>
 *
 * <p><b>实现边界（诚实声明）</b>：
 * <ul>
 *   <li>触发精度为「分钟」（cron 的最小精度即分钟）：同一分钟窗口只触发一次；</li>
 *   <li>去重为进程内内存映射（{@code lastFiredAt}）——<b>不跨副本</b>。本模块部署形态
 *       为单副本（chart 默认 replicaCount=1）；多副本需引入分布式锁，属后续项；</li>
 *   <li>重启不补跑：首见作业只登记基准时刻、不触发；错过的窗口不追溯；</li>
 *   <li>单个作业触发失败仅告警，不中断其余作业与后续轮询。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.job.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class JobScheduleRunner {

    private final JobRepository jobRepository;
    private final JobService jobService;

    /** jobId → 最近一次判定/触发时刻（进程内去重；见类注释的边界声明）。包内可见：供单测拨动时间基准。 */
    final Map<Long, Instant> lastFiredAt = new ConcurrentHashMap<>();

    /** 周期扫描（默认 30s 一轮；interval 与 initial-delay 均可配）。 */
    @Scheduled(
            fixedDelayString = "${app.job.scheduler.poll-interval-ms:30000}",
            initialDelayString = "${app.job.scheduler.initial-delay-ms:15000}")
    public void poll() {
        List<JobEntity> candidates;
        try {
            candidates = jobRepository.findByScheduleIsNotNull();
        } catch (Exception e) {
            // 数据层异常（如库未就绪）只告警：调度不得拖垮主进程
            log.warn("调度扫描查询失败: {}", e.getMessage());
            return;
        }
        if (candidates.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (JobEntity job : candidates) {
            try {
                Instant last = lastFiredAt.get(job.getId());
                if (last == null) {
                    // 首见：只登记基准时刻（不追溯补跑），下一轮起参与判定
                    if (isSchedulable(job)) {
                        lastFiredAt.put(job.getId(), now);
                    }
                    continue;
                }
                if (!shouldFire(job.getSchedule(), job.getStatus(), last, now)) {
                    continue;
                }
                lastFiredAt.put(job.getId(), now);
                // 调度线程无请求上下文：显式注入作业所属租户，执行后必须清理
                TenantContext.setTenantId(job.getTenantId());
                try {
                    jobService.runScheduled(job.getId());
                } finally {
                    TenantContext.clear();
                }
                log.info("调度触发作业: jobId={}, name={}, schedule={}, tenant={}",
                        job.getId(), job.getName(), job.getSchedule(), job.getTenantId());
            } catch (Exception e) {
                log.warn("调度触发作业失败: jobId={}, err={}", job.getId(), e.getMessage());
            }
        }
    }

    /** 作业是否具备被调度资格（schedule 非空 + active）。 */
    static boolean isSchedulable(JobEntity job) {
        return job.getSchedule() != null && !job.getSchedule().isBlank()
                && "active".equals(job.getStatus());
    }

    /**
     * 触发判据（包私有静态，便于单测直测）。
     *
     * <p>规则：仅 {@code active} 作业参与；cron 非法不触发；{@code lastFiredAt} 为空
     * （首见）不触发；同一分钟窗口只触发一次；且「上一个触发点 ≤ now」才触发。</p>
     *
     * @param schedule    cron 表达式（可空）
     * @param status      作业状态（仅 active 参与）
     * @param lastFiredAt 上一次判定/触发时刻（首见为空）
     * @param now         当前时刻
     * @return 是否应在本轮触发
     */
    static boolean shouldFire(String schedule, String status, Instant lastFiredAt, Instant now) {
        if (schedule == null || schedule.isBlank()) {
            return false;
        }
        if (!"active".equals(status)) {
            return false;
        }
        if (lastFiredAt == null) {
            return false;
        }
        // 同一分钟窗口只触发一次（cron 最小精度为分钟）
        if (!lastFiredAt.isBefore(now.truncatedTo(ChronoUnit.MINUTES))) {
            return false;
        }
        CronExpression cron = parseCron(schedule);
        if (cron == null) {
            return false;
        }
        // Spring CronExpression.next 需要含日期字段的 Temporal（对 Instant 会抛
        // UnsupportedTemporalTypeException: DayOfWeek）⇒ 以 JVM 默认时区求下一次
        // 触发点，再折回 Instant 比较。日级 cron（如「每日 0 点」）语义即服务器本地 0 点。
        ZonedDateTime base = lastFiredAt.atZone(ZoneId.systemDefault());
        ZonedDateTime next = cron.next(base);
        return next != null && !next.toInstant().isAfter(now);
    }

    /**
     * 解析 cron 表达式（兼容 5 段与 6 段两种惯例）。
     *
     * <p>Linux 惯例为 5 段（分 时 日 月 周），Spring {@link CronExpression} 要求
     * 6 段（秒 分 时 日 月 周）——本方法对 5 段表达式补「秒 = 0」后解析，
     * 使前端与存量数据里的两种写法都能被调度。</p>
     *
     * @param raw 原始表达式
     * @return 解析结果；段数或取值非法时返回 {@code null}
     */
    static CronExpression parseCron(String raw) {
        String s = raw.trim().replaceAll("\\s+", " ");
        int fields = s.split(" ").length;
        if (fields == 5) {
            s = "0 " + s; // 5 段（Linux）→ 6 段（Spring）：秒位补 0
        } else if (fields != 6) {
            return null;
        }
        try {
            return CronExpression.parse(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
