package com.shuqing.bigdata.sqlgateway.rewrite.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RewriteConfig 单元测试。
 *
 * @author shuqing-bigdata
 */
class RewriteConfigTest {

    @Test
    @DisplayName("默认构造 — 应使用默认值")
    void defaultConstructor_shouldUseDefaults() {
        RewriteConfig config = new RewriteConfig();
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getMinMatchScore()).isEqualTo(0.6);
        assertThat(config.getMaxCandidates()).isEqualTo(50);
        assertThat(config.isStrictEquivalence()).isTrue();
        assertThat(config.getExcludedTables()).isEmpty();
    }

    @Test
    @DisplayName("isExcluded — 排除列表含指定表时返回 true")
    void isExcluded_tableInList_shouldReturnTrue() {
        RewriteConfig config = new RewriteConfig();
        config.setExcludedTables(List.of("information_schema", "pg_catalog"));

        assertThat(config.isExcluded("information_schema")).isTrue();
        assertThat(config.isExcluded("pg_catalog")).isTrue();
    }

    @Test
    @DisplayName("isExcluded — 大小写不敏感")
    void isExcluded_caseInsensitive_shouldReturnTrue() {
        RewriteConfig config = new RewriteConfig();
        config.setExcludedTables(List.of("Information_Schema"));

        assertThat(config.isExcluded("information_schema")).isTrue();
    }

    @Test
    @DisplayName("isExcluded — 不在排除列表返回 false")
    void isExcluded_tableNotInList_shouldReturnFalse() {
        RewriteConfig config = new RewriteConfig();
        config.setExcludedTables(List.of("information_schema"));

        assertThat(config.isExcluded("sales")).isFalse();
    }

    @Test
    @DisplayName("isExcluded — 空排除列表返回 false")
    void isExcluded_emptyList_shouldReturnFalse() {
        RewriteConfig config = new RewriteConfig();
        assertThat(config.isExcluded("sales")).isFalse();
    }

    @Test
    @DisplayName("isExcluded — null 表名返回 false")
    void isExcluded_nullTable_shouldReturnFalse() {
        RewriteConfig config = new RewriteConfig();
        config.setExcludedTables(List.of("sales"));
        assertThat(config.isExcluded(null)).isFalse();
    }

    @Test
    @DisplayName("setter/getter — 设置并读取配置值")
    void setters_shouldUpdateValues() {
        RewriteConfig config = new RewriteConfig();
        config.setEnabled(false);
        config.setMinMatchScore(0.8);
        config.setMaxCandidates(100);
        config.setStrictEquivalence(false);
        config.setExcludedTables(List.of("t1", "t2"));

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getMinMatchScore()).isEqualTo(0.8);
        assertThat(config.getMaxCandidates()).isEqualTo(100);
        assertThat(config.isStrictEquivalence()).isFalse();
        assertThat(config.getExcludedTables()).containsExactly("t1", "t2");
    }
}