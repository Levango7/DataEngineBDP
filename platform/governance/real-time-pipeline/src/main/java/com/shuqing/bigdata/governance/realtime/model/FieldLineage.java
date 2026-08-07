package com.shuqing.bigdata.governance.realtime.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * 字段级血缘关系。
 *
 * <p>由 {@code LineageAnalyzer} 解析 Flink CDC SQL 提取，描述源表字段到目标表字段的
 * 数据流向，写入 NebulaGraph 血缘图。
 *
 * <p>血缘粒度：
 * <ul>
 *   <li>表级：sourceTable → targetTable</li>
 *   <li>字段级：sourceTable.field → targetTable.field（多对多）</li>
 *   <li>转换表达式：记录字段间的转换逻辑（如 {@code target.age = source.age + 1}）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldLineage implements Serializable {

    /** 血缘记录唯一 ID（UUID） */
    private String lineageId;

    /** 源表标识符 */
    private String sourceTable;

    /** 目标表标识符 */
    private String targetTable;

    /** 字段级血缘映射列表 */
    private List<FieldMapping> fieldMappings;

    /** Flink CDC SQL 作业 ID */
    private String jobId;

    /** Flink CDC SQL 文本（用于追溯） */
    private String sqlText;

    /** 血缘提取时间戳 */
    private Instant extractedAt;

    /** 提取耗时（毫秒） */
    private long extractDurationMs;

    /** 字段映射内嵌类 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldMapping implements Serializable {
        /** 源字段名 */
        private String sourceField;
        /** 目标字段名 */
        private String targetField;
        /** 转换类型：DIRECT（直接映射）、TRANSFORM（转换）、CONSTANT（常量）、AGGREGATE（聚合） */
        private String transformType;
        /** 转换表达式（DIRECT 时为 null，TRANSFORM 时为表达式文本） */
        private String expression;
    }
}