package com.shuqing.bigdata.governance.collector.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 采集历史记录。
 *
 * <p>每次采集（手动触发或定时调度）写入一条记录，用于查询采集状态、审计与重试。
 * 通过 {@code CollectionSchedulerService#getCollectionStatus} 暴露给上层。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "collection_history")
public class CollectionHistory {

    /** 自增主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 数据源 ID */
    private Long sourceId;

    /** 触发方式：MANUAL/SCHEDULED */
    private String triggerType;

    /** 采集状态：RUNNING/SUCCESS/FAILED */
    private String status;

    /** 采集开始时间 */
    private LocalDateTime startedAt;

    /** 采集结束时间 */
    private LocalDateTime finishedAt;

    /** 采集耗时（毫秒） */
    private Long durationMs;

    /** 采集到的表数 */
    private Integer tableCount;

    /** 采集到的列数 */
    private Integer columnCount;

    /** 错误信息（失败时填充） */
    @Lob
    private String errorMessage;
}