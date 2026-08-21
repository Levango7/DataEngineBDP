package com.shuqing.bigdata.sqlgateway.rewrite.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询改写与物化视图自动路由配置。
 *
 * <p>绑定 {@code sql-gateway.rewrite.*} 配置项，控制改写器与路由器的行为：</p>
 * <ul>
 *   <li>{@code enabled}：是否启用查询改写（默认 true）；</li>
 *   <li>{@code min-match-score}：最小匹配评分阈值，低于此值不路由（默认 0.6）；</li>
 *   <li>{@code max-candidates}：单次匹配最大候选视图数（默认 50，避免扫描过多）；</li>
 *   <li>{@code strict-equivalence}：是否严格等价校验（默认 true，保证用户无感知）；</li>
 *   <li>{@code excluded-tables}：排除自动路由的表名列表（如系统表）。</li>
 * </ul>
 *
 * <p>配置示例（application.yml）：</p>
 * <pre>
 * sql-gateway:
 *   rewrite:
 *     enabled: true
 *     min-match-score: 0.6
 *     max-candidates: 50
 *     strict-equivalence: true
 *     excluded-tables:
 *       - information_schema
 *       - pg_catalog
 * </pre>
 *
 * @author shuqing-bigdata
 */
@Configuration
@ConfigurationProperties(prefix = "sql-gateway.rewrite")
public class RewriteConfig {

    /**
     * 是否启用查询改写。
     */
    private boolean enabled = true;

    /**
     * 最小匹配评分阈值，低于此值的弱匹配不参与路由。
     */
    private double minMatchScore = 0.6;

    /**
     * 单次匹配最大候选视图数。
     */
    private int maxCandidates = 50;

    /**
     * 是否严格等价校验（true 时仅返回保证等价的改写）。
     */
    private boolean strictEquivalence = true;

    /**
     * 排除自动路由的表名列表（小写匹配）。
     */
    private List<String> excludedTables = new ArrayList<>();

    /**
     * 默认构造器。
     */
    public RewriteConfig() {
    }

    /**
     * 获取是否启用查询改写。
     *
     * @return {@code true} 表示启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置是否启用查询改写。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 获取最小匹配评分阈值。
     *
     * @return 阈值
     */
    public double getMinMatchScore() {
        return minMatchScore;
    }

    /**
     * 设置最小匹配评分阈值。
     *
     * @param minMatchScore 阈值
     */
    public void setMinMatchScore(double minMatchScore) {
        this.minMatchScore = minMatchScore;
    }

    /**
     * 获取单次匹配最大候选视图数。
     *
     * @return 最大候选数
     */
    public int getMaxCandidates() {
        return maxCandidates;
    }

    /**
     * 设置单次匹配最大候选视图数。
     *
     * @param maxCandidates 最大候选数
     */
    public void setMaxCandidates(int maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    /**
     * 获取是否严格等价校验。
     *
     * @return {@code true} 表示严格等价
     */
    public boolean isStrictEquivalence() {
        return strictEquivalence;
    }

    /**
     * 设置是否严格等价校验。
     *
     * @param strictEquivalence 是否严格等价
     */
    public void setStrictEquivalence(boolean strictEquivalence) {
        this.strictEquivalence = strictEquivalence;
    }

    /**
     * 获取排除自动路由的表名列表。
     *
     * @return 排除表名列表
     */
    public List<String> getExcludedTables() {
        return excludedTables;
    }

    /**
     * 设置排除自动路由的表名列表。
     *
     * @param excludedTables 排除表名列表
     */
    public void setExcludedTables(List<String> excludedTables) {
        this.excludedTables = excludedTables;
    }

    /**
     * 判断指定表名是否被排除自动路由。
     *
     * @param tableName 表名
     * @return {@code true} 表示被排除
     */
    public boolean isExcluded(String tableName) {
        if (tableName == null || excludedTables == null || excludedTables.isEmpty()) {
            return false;
        }
        String lower = tableName.toLowerCase(java.util.Locale.ROOT);
        for (String excluded : excludedTables) {
            if (excluded != null && excluded.toLowerCase(java.util.Locale.ROOT).equals(lower)) {
                return true;
            }
        }
        return false;
    }
}