package com.shuqing.bigdata.sqlgateway.calcite.explain;

/**
 * EXPLAIN 输出格式枚举——支持树形、JSON、表格式三种可视化形式。
 *
 * <p>本枚举由 {@link ExplainFormatter} 与 {@link ExplainVisualizer} 使用，
 * 决定 EXPLAIN 执行计划的渲染方式：</p>
 * <ul>
 *   <li>{@link #TREE}：缩进式树形文本，直观展示 RelNode 层级与下推标注</li>
 *   <li>{@link #JSON}：结构化 JSON，便于程序解析与前端渲染</li>
 *   <li>{@link #TABLE}：ASCII 表格，按节点逐行展示属性列</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
public enum ExplainFormat {

    /** 树形文本：缩进 + 连接符（├─ └─）展示 RelNode 层级 */
    TREE,

    /** JSON 格式：结构化对象，含节点、子节点、统计信息 */
    JSON,

    /** 表格格式：ASCII 表格，每行一个节点，列含 ID/Op/Table/Source/PushDown/Cost */
    TABLE;

    /**
     * 大小写无关解析格式名称，未识别时抛出 {@link IllegalArgumentException}。
     *
     * @param name 格式名称
     * @return 解析得到的格式枚举
     */
    public static ExplainFormat fromString(String name) {
        if (name == null || name.isBlank()) {
            return TREE;
        }
        return valueOf(name.trim().toUpperCase());
    }
}