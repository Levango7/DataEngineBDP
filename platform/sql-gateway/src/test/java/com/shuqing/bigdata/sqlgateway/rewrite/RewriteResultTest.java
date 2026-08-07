package com.shuqing.bigdata.sqlgateway.rewrite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RewriteResult 单元测试。
 *
 * <p>验证工厂方法 {@link RewriteResult#notRewritten} 与 {@link RewriteResult#failure}
 * 的行为是否符合预期。</p>
 *
 * @author shuqing-bigdata
 */
class RewriteResultTest {

    @Test
    @DisplayName("notRewritten — 透传原始 SQL 并标记未改写")
    void notRewritten_shouldPassThroughSql() {
        String sql = "SELECT * FROM sales";
        RewriteResult result = RewriteResult.notRewritten(sql);

        assertThat(result.getOriginalSql()).isEqualTo(sql);
        assertThat(result.getRewrittenSql()).isEqualTo(sql);
        assertThat(result.isRewritten()).isFalse();
        assertThat(result.getMatchedView()).isNull();
        assertThat(result.isEquivalent()).isTrue();
        assertThat(result.getMatchScore()).isEqualTo(0.0);
        assertThat(result.getRulesApplied()).isEmpty();
        assertThat(result.getCandidateViews()).isEmpty();
        assertThat(result.getReason()).contains("未命中");
    }

    @Test
    @DisplayName("failure — 标记失败并填充错误信息")
    void failure_shouldMarkFailedAndFillError() {
        String sql = "SELECT * FROM sales";
        RewriteResult result = RewriteResult.failure(sql, "解析失败");

        assertThat(result.getOriginalSql()).isEqualTo(sql);
        assertThat(result.getRewrittenSql()).isEqualTo(sql);
        assertThat(result.isRewritten()).isFalse();
        assertThat(result.isEquivalent()).isFalse();
        assertThat(result.getError()).isEqualTo("解析失败");
        assertThat(result.getReason()).contains("解析失败");
    }

    @Test
    @DisplayName("builder — 完整构造改写成功结果")
    void builder_shouldBuildSuccessfulResult() {
        RewriteResult result = RewriteResult.builder()
                .originalSql("SELECT * FROM sales")
                .rewrittenSql("SELECT * FROM mv_sales_daily")
                .rewritten(true)
                .matchedView("mv_sales_daily")
                .rulesApplied(List.of("EXACT_MATCH"))
                .reason("完全匹配")
                .matchScore(1.0)
                .equivalent(true)
                .durationMs(15L)
                .candidateViews(List.of("mv_sales_daily"))
                .build();

        assertThat(result.isRewritten()).isTrue();
        assertThat(result.getMatchedView()).isEqualTo("mv_sales_daily");
        assertThat(result.getRulesApplied()).containsExactly("EXACT_MATCH");
        assertThat(result.getMatchScore()).isEqualTo(1.0);
        assertThat(result.getDurationMs()).isEqualTo(15L);
    }
}