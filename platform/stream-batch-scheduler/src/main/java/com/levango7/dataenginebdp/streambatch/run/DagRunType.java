package com.levango7.dataenginebdp.streambatch.run;

/**
 * DAG 运行类型。
 *
 * <p>区分实例来源：手动触发、调度触发、失败重跑、补数据回填。
 */
public enum DagRunType {
    /** 手动触发（人为点击 / API 提交）。 */
    MANUAL,
    /** 调度器周期触发。 */
    SCHEDULED,
    /** 基于历史 runId 的失败重跑。 */
    RERUN,
    /** 按时间区间补数据生成的回填实例。 */
    BACKFILL
}
