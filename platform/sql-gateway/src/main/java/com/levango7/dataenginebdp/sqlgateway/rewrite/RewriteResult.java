package com.levango7.dataenginebdp.sqlgateway.rewrite;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 查询改写结果。
 *
 * <p>封装从原始 SQL 到改写后 SQL 的完整信息，包括：</p>
 * <ul>
 *   <li>原始 SQL 与改写后 SQL（若未命中任何物化视图则二者相同）；</li>
 *   <li>是否发生改写、命中的物化视图名称、应用的改写规则；</li>
 *   <li>改写原因与匹配评分，便于调试与可观测性；</li>
 *   <li>等价性保证标记，标识改写后查询结果与原查询语义一致。</li>
 * </ul>
 *
 * <p>本类为不可变值对象，通过 {@link #builder()} 构造。</p>
 *
 * @author shuqing-bigdata
 */
@Data
@Builder
public class RewriteResult {

    /** 原始 SQL */
    private final String originalSql;

    /** 改写后 SQL（未改写时与 originalSql 相同） */
    private final String rewrittenSql;

    /** 是否发生改写 */
    private final boolean rewritten;

    /** 命中的物化视图名称（未命中时为 null） */
    private final String matchedView;

    /** 应用的改写规则名称列表 */
    @Builder.Default
    private final List<String> rulesApplied = new ArrayList<>();

    /** 改写原因描述（人类可读） */
    private final String reason;

    /** 匹配评分（0~1，越高表示匹配越优） */
    private final double matchScore;

    /** 改写是否保证结果等价（true 表示可安全路由） */
    private final boolean equivalent;

    /** 改写耗时（毫秒） */
    private final long durationMs;

    /** 候选物化视图列表（按匹配评分降序，便于调试） */
    @Builder.Default
    private final List<String> candidateViews = new ArrayList<>();

    /** 错误信息（改写失败时填充） */
    private final String error;

    /**
     * 构造一个未改写的结果（透传原始 SQL）。
     *
     * @param sql 原始 SQL
     * @return 未改写结果
     */
    public static RewriteResult notRewritten(String sql) {
        return RewriteResult.builder()
                .originalSql(sql)
                .rewrittenSql(sql)
                .rewritten(false)
                .rulesApplied(Collections.emptyList())
                .reason("未命中任何物化视图")
                .matchScore(0.0)
                .equivalent(true)
                .durationMs(0L)
                .candidateViews(Collections.emptyList())
                .build();
    }

    /**
     * 构造一个改写失败的结果。
     *
     * @param sql   原始 SQL
     * @param error 错误信息
     * @return 失败结果
     */
    public static RewriteResult failure(String sql, String error) {
        return RewriteResult.builder()
                .originalSql(sql)
                .rewrittenSql(sql)
                .rewritten(false)
                .rulesApplied(Collections.emptyList())
                .reason("改写失败: " + error)
                .matchScore(0.0)
                .equivalent(false)
                .durationMs(0L)
                .candidateViews(Collections.emptyList())
                .error(error)
                .build();
    }
}