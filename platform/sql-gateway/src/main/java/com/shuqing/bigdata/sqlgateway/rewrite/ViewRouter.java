package com.shuqing.bigdata.sqlgateway.rewrite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 物化视图自动路由决策器。
 *
 * <p>基于 {@link ViewMatcher} 的匹配结果，结合改写规则、视图优先级、刷新时间等元数据，
 * 选择最优的物化视图进行路由。</p>
 *
 * <p>决策流程：</p>
 * <ol>
 *   <li>调用 {@link ViewMatcher#matchAll} 获取所有候选匹配结果（已按评分降序）；</li>
 *   <li>过滤掉评分低于阈值 {@code minMatchScore} 的弱匹配；</li>
 *   <li>结合 {@link RewriteRule} 中显式声明的规则优先级与目标视图，加权排序；</li>
 *   <li>返回最优匹配；若无可信匹配则返回 {@link Optional#empty()}，保持原始查询。</li>
 * </ol>
 *
 * <p>"用户无感知"约束：仅当评分 ≥ 阈值且视图可用时才路由，否则透传原始 SQL。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class ViewRouter {

    private static final Logger log = LoggerFactory.getLogger(ViewRouter.class);

    /**
     * 默认最小匹配评分阈值，低于此值的弱匹配不参与路由。
     */
    public static final double DEFAULT_MIN_MATCH_SCORE = 0.6;

    private final ViewMatcher viewMatcher;

    /**
     * 最小匹配评分阈值，从配置 {@code sql-gateway.rewrite.min-match-score} 读取。
     */
    @Value("${sql-gateway.rewrite.min-match-score:0.6}")
    private double minMatchScore = DEFAULT_MIN_MATCH_SCORE;

    /**
     * 构造路由器。
     *
     * @param viewMatcher 物化视图匹配器
     */
    public ViewRouter(ViewMatcher viewMatcher) {
        this.viewMatcher = viewMatcher;
    }

    /**
     * 设置最小匹配评分阈值（主要用于测试覆盖）。
     *
     * @param minMatchScore 阈值
     */
    public void setMinMatchScore(double minMatchScore) {
        this.minMatchScore = minMatchScore;
    }

    /**
     * 获取当前最小匹配评分阈值。
     *
     * @return 阈值
     */
    public double getMinMatchScore() {
        return minMatchScore;
    }

    /**
     * 对用户查询执行自动路由决策。
     *
     * <p>不应用显式改写规则，仅基于匹配评分与视图优先级选择最优视图。</p>
     *
     * @param sql        用户查询 SQL
     * @param candidates 候选物化视图列表
     * @return 最优匹配结果；无可靠匹配返回 {@link Optional#empty()}
     */
    public Optional<ViewMatcher.MatchResult> route(String sql,
                                                   List<MaterializedViewDefinition> candidates) {
        return route(sql, candidates, List.of());
    }

    /**
     * 对用户查询执行自动路由决策，结合显式改写规则加权。
     *
     * <p>若改写规则中存在显式指向某视图的规则，且该视图在候选匹配结果中，
     * 则提升其优先级（评分 + 0.1 加权）。</p>
     *
     * @param sql        用户查询 SQL
     * @param candidates 候选物化视图列表
     * @param rules      改写规则列表（可为空）
     * @return 最优匹配结果；无可靠匹配返回 {@link Optional#empty()}
     */
    public Optional<ViewMatcher.MatchResult> route(String sql,
                                                   List<MaterializedViewDefinition> candidates,
                                                   List<RewriteRule> rules) {
        if (sql == null || sql.isBlank() || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }

        List<ViewMatcher.MatchResult> matches = viewMatcher.matchAll(sql, candidates);
        if (matches.isEmpty()) {
            log.debug("路由决策：无匹配候选 sql={}{}", abbreviate(sql, 60), "");
            return Optional.empty();
        }

        // 过滤低于阈值的弱匹配
        List<ViewMatcher.MatchResult> qualified = matches.stream()
                .filter(m -> m.score() >= minMatchScore)
                .toList();
        if (qualified.isEmpty()) {
            log.debug("路由决策：所有匹配评分低于阈值 {} sql={}", minMatchScore, abbreviate(sql, 60));
            return Optional.empty();
        }

        // 应用显式规则加权
        ViewMatcher.MatchResult best = selectBest(qualified, rules);
        log.info("路由决策：选中视图={} 评分={} 类型={} 原因={}",
                best.viewName(), best.score(), best.ruleType(), best.reason());
        return Optional.of(best);
    }

    /**
     * 在合格匹配结果中选择最优，结合显式规则加权。
     *
     * <p>加权策略：若存在 {@link RewriteRule#getTargetView()} 等于匹配视图名且规则启用，
     * 则该匹配评分加权 +0.1（不超过 1.0）。最终按加权后评分降序选择第一个。</p>
     *
     * @param qualified 合格匹配列表（已按原评分降序）
     * @param rules     改写规则列表
     * @return 最优匹配
     */
    private ViewMatcher.MatchResult selectBest(List<ViewMatcher.MatchResult> qualified,
                                               List<RewriteRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return qualified.get(0);
        }

        ViewMatcher.MatchResult best = null;
        double bestScore = -1;
        for (ViewMatcher.MatchResult m : qualified) {
            double weighted = m.score();
            for (RewriteRule rule : rules) {
                if (rule == null || !Boolean.TRUE.equals(rule.getEnabled())) {
                    continue;
                }
                if (rule.getTargetView() != null
                        && rule.getTargetView().equalsIgnoreCase(m.viewName())) {
                    weighted = Math.min(1.0, weighted + 0.1);
                    break;
                }
            }
            if (weighted > bestScore) {
                bestScore = weighted;
                best = m;
            }
        }
        return best;
    }

    /**
     * 截断 SQL 用于日志输出。
     *
     * @param s      原始字符串
     * @param maxLen 最大长度
     * @return 截断后的字符串
     */
    private String abbreviate(String s, int maxLen) {
        if (s == null) {
            return "<null>";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}