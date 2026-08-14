package com.levango7.dataenginebdp.tagengine.store.doris;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DorisSqlGenerator 标签计算 SQL 生成测试（5.3 修复）。
 *
 * <p>Doris 使用 MySQL 方言：标识符反引号包裹。</p>
 */
class DorisSqlGeneratorComputeTest {

    private final DorisSqlGenerator generator = new DorisSqlGenerator();

    @Test
    void buildTagComputeSql_generatesCaseWhenUpsert() {
        var sql = generator.buildTagComputeSql(
                "tag_wide",
                "tag_level",
                List.of(
                        new DorisSqlGenerator.TagRuleSql("total_amount > 10000", "VIP"),
                        new DorisSqlGenerator.TagRuleSql("total_amount > 5000", "GOLD")
                ),
                "UNKNOWN");

        assertThat(sql.sql()).isEqualTo(
                "INSERT INTO `tag_wide` (user_id, `tag_level`) SELECT user_id, "
                        + "CASE WHEN total_amount > 10000 THEN ? WHEN total_amount > 5000 THEN ? "
                        + "ELSE ? END FROM `tag_wide`");
        // 参数: VIP, GOLD, UNKNOWN
        assertThat(sql.params()).hasSize(3);
        assertThat(sql.params().get(0)).isEqualTo("VIP");
        assertThat(sql.params().get(2)).isEqualTo("UNKNOWN");
    }

    @Test
    void buildTagComputeSql_emptyRulesUsesDefault() {
        var sql = generator.buildTagComputeSql("tag_wide", "tag_level", List.of(), "DEFAULT");

        assertThat(sql.sql()).isEqualTo(
                "INSERT INTO `tag_wide` (user_id, `tag_level`) SELECT user_id, ? FROM `tag_wide`");
        assertThat(sql.params()).containsExactly("DEFAULT");
    }

    @Test
    void buildTagComputeSql_quotesColumnAndTable() {
        // 防注入：表名/列名反引号包裹（恶意片段整体成为单个标识符，不构成语句注入）
        var sql = generator.buildTagComputeSql(
                "tag; DROP TABLE users;", "bad_col", List.of(), "x");

        assertThat(sql.sql()).startsWith("INSERT INTO `tag; DROP TABLE users;`");
        assertThat(sql.sql()).contains("`bad_col`");
        // 恶意片段整体被反引号包裹：反引号内出现分号仍是标识符，Doris 不会拆分为多条语句
        assertThat(sql.sql()).contains("`tag; DROP TABLE users;`");
        // 反引号闭合后不能紧跟裸 SQL 片段（防逃逸）
        assertThat(sql.sql()).doesNotContain("` ; DROP");
    }

    @Test
    void buildTagComputeSql_tagColumnWithNumbers() {
        var sql = generator.buildTagComputeSql("tag_wide", "tag_123", List.of(), "x");
        assertThat(sql.sql()).contains("`tag_123`");
    }
}
