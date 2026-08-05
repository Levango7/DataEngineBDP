package com.shuqing.bigdata.tagengine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 标签查询条件。
 *
 * <p>用于按标签组合圈选用户，支持 AND/OR 逻辑运算与嵌套。
 * 对应详细设计 §5 人群圈选。</p>
 *
 * <p>结构示例：</p>
 * <pre>
 * TagQuery {
 *   tenantId: "t1",
 *   logic: "AND",
 *   conditions: [
 *     { columnName: "user_level",   op: "=",   value: "活跃" },
 *     { columnName: "total_amount", op: ">=",  value: 5000 },
 *     { columnName: "price_sens",   op: "IN",  value: [1, 2] }
 *   ],
 *   nested: [ ... ]   // 可嵌套子查询
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TagQuery {

    /** 租户 ID */
    private String tenantId;

    /** 顶层逻辑运算符：AND / OR */
    private String logic;

    /** 标签条件列表 */
    private List<Condition> conditions;

    /** 嵌套子查询（与 conditions 同层参与 logic 运算） */
    private List<TagQuery> nested;

    /**
     * 单个标签条件。
     *
     * <p>支持的 op：=、!=、&gt;、&gt;=、&lt;、&lt;=、IN、NOT IN、BETWEEN、LIKE、IS NULL、IS NOT NULL。</p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Condition {

        /** 标签列名（对应 Doris 宽表字段） */
        private String columnName;

        /** 比较运算符 */
        private String op;

        /** 比较值；op=IN/NOT IN 时为 List，op=BETWEEN 时为 [lo, hi] */
        private Object value;
    }
}