package com.shuqing.bigdata.sqlgateway.rewrite;

/**
 * 改写规则类型。
 *
 * <p>定义查询改写器支持的改写策略类型，每种类型对应一种物化视图匹配与替换模式。</p>
 *
 * @author shuqing-bigdata
 */
public enum RewriteRuleType {

    /**
     * 完全匹配：查询与物化视图定义完全一致，直接替换 FROM 子句的表名为视图名。
     */
    EXACT_MATCH,

    /**
     * 聚合下推：查询的聚合粒度比物化视图更粗（维度子集），可基于物化视图二次聚合。
     */
    AGG_ROLLUP,

    /**
     * 谓词加细：查询的过滤条件比物化视图更严格（谓词超集），可在视图上追加过滤。
     */
    PREDICATE_REFINEMENT,

    /**
     * 投影裁剪：查询的列是物化视图列的子集，可在视图上裁剪列。
     */
    PROJECTION_PRUNING,

    /**
     * 复合改写：同时满足上述多种条件。
     */
    COMPOUND
}