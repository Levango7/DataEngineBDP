package com.levango7.dataenginebdp.sqlgateway.crosssource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ColumnAligner} 单元测试。
 *
 * <p>覆盖跨源归并中的列对齐逻辑，包括：</p>
 * <ul>
 *   <li>列顺序不一致时按列名对齐；</li>
 *   <li>列名大小写不一致时按列名对齐；</li>
 *   <li>缺失列填充 null；</li>
 *   <li>并集列定义计算；</li>
 *   <li>兼容性校验。</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
class ColumnAlignerTest {

    // ===================== alignRow =====================

    @Test
    @DisplayName("alignRow — 列顺序一致时直接复制")
    void alignRow_sameOrder() {
        List<Object> aligned = ColumnAligner.alignRow(
                List.of("id", "name"),
                List.of("id", "name"),
                List.of(1, "alice"));

        assertThat(aligned).containsExactly(1, "alice");
    }

    @Test
    @DisplayName("alignRow — 列顺序不一致时按列名重新排列")
    void alignRow_differentOrder() {
        // 目标列 [id, name]，源列 [name, id]，源行 ["bob", 2]
        // 对齐后应为 [2, "bob"]
        List<Object> aligned = ColumnAligner.alignRow(
                List.of("id", "name"),
                List.of("name", "id"),
                List.of("bob", 2));

        assertThat(aligned).containsExactly(2, "bob");
    }

    @Test
    @DisplayName("alignRow — 列名大小写不一致时按列名对齐")
    void alignRow_caseInsensitive() {
        // 目标列 [id, name]，源列 [ID, NAME]，源行 [1, "alice"]
        List<Object> aligned = ColumnAligner.alignRow(
                List.of("id", "name"),
                List.of("ID", "NAME"),
                List.of(1, "alice"));

        assertThat(aligned).containsExactly(1, "alice");
    }

    @Test
    @DisplayName("alignRow — 源缺少目标列时填充 null")
    void alignRow_missingColumnFilledWithNull() {
        // 目标列 [id, name, age]，源列 [id, name]，源行 [1, "alice"]
        // 对齐后应为 [1, "alice", null]
        List<Object> aligned = ColumnAligner.alignRow(
                List.of("id", "name", "age"),
                List.of("id", "name"),
                List.of(1, "alice"));

        assertThat(aligned).containsExactly(1, "alice", null);
    }

    @Test
    @DisplayName("alignRow — 源有多余列时忽略")
    void alignRow_extraColumnIgnored() {
        // 目标列 [id, name]，源列 [id, name, age]，源行 [1, "alice", 30]
        // 对齐后应为 [1, "alice"]
        List<Object> aligned = ColumnAligner.alignRow(
                List.of("id", "name"),
                List.of("id", "name", "age"),
                List.of(1, "alice", 30));

        assertThat(aligned).containsExactly(1, "alice");
    }

    @Test
    @DisplayName("alignRow — 空目标列返回空列表")
    void alignRow_emptyTargetColumns() {
        List<Object> aligned = ColumnAligner.alignRow(
                List.of(),
                List.of("id"),
                List.of(1));

        assertThat(aligned).isEmpty();
    }

    @Test
    @DisplayName("alignRow — null 源行返回全 null 列表")
    void alignRow_nullSourceRow() {
        List<Object> aligned = ColumnAligner.alignRow(
                List.of("id", "name"),
                List.of("id", "name"),
                null);

        assertThat(aligned).containsExactly(null, null);
    }

    @Test
    @DisplayName("alignRow — 列名带空格时 trim 后匹配")
    void alignRow_columnNameWithSpaces() {
        List<Object> aligned = ColumnAligner.alignRow(
                List.of("  id  ", "name"),
                List.of("id", "  name  "),
                List.of(1, "alice"));

        assertThat(aligned).containsExactly(1, "alice");
    }

    // ===================== alignResult =====================

    @Test
    @DisplayName("alignResult — 整个结果集列对齐")
    void alignResult_fullAlignment() {
        MergeResult source = new MergeResult(
                List.of("name", "id"),
                List.of(List.of("alice", 1), List.of("bob", 2)),
                "trino", 0);

        MergeResult aligned = ColumnAligner.alignResult(List.of("id", "name"), source);

        assertThat(aligned.getColumns()).containsExactly("id", "name");
        assertThat(aligned.getRows().get(0)).containsExactly(1, "alice");
        assertThat(aligned.getRows().get(1)).containsExactly(2, "bob");
        assertThat(aligned.getSource()).isEqualTo("trino");
    }

    @Test
    @DisplayName("alignResult — null 输入返回空结果")
    void alignResult_nullInput() {
        MergeResult result = ColumnAligner.alignResult(List.of("id"), null);
        assertThat(result.getRowCount()).isZero();
        assertThat(result.getColumns()).containsExactly("id");
    }

    // ===================== unionColumns =====================

    @Test
    @DisplayName("unionColumns — 列名相同的多个结果集")
    void unionColumns_sameColumns() {
        List<MergeResult> results = List.of(
                new MergeResult(List.of("id", "name"), List.of(), "trino", 0),
                new MergeResult(List.of("id", "name"), List.of(), "doris", 0));

        List<String> union = ColumnAligner.unionColumns(results);
        assertThat(union).containsExactly("id", "name");
    }

    @Test
    @DisplayName("unionColumns — 列顺序不同的多个结果集保留首次出现顺序")
    void unionColumns_differentOrder() {
        List<MergeResult> results = List.of(
                new MergeResult(List.of("id", "name"), List.of(), "trino", 0),
                new MergeResult(List.of("name", "id"), List.of(), "doris", 0));

        List<String> union = ColumnAligner.unionColumns(results);
        assertThat(union).containsExactly("id", "name");
    }

    @Test
    @DisplayName("unionColumns — 列名大小写不同时去重保留首次出现形式")
    void unionColumns_caseInsensitiveDedup() {
        List<MergeResult> results = List.of(
                new MergeResult(List.of("ID", "Name"), List.of(), "trino", 0),
                new MergeResult(List.of("id", "name"), List.of(), "doris", 0));

        List<String> union = ColumnAligner.unionColumns(results);
        // 首次出现是 ID, Name，后续 id, name 应被去重
        assertThat(union).containsExactly("ID", "Name");
    }

    @Test
    @DisplayName("unionColumns — 不同列名取并集")
    void unionColumns_distinctColumns() {
        List<MergeResult> results = List.of(
                new MergeResult(List.of("id", "name"), List.of(), "trino", 0),
                new MergeResult(List.of("id", "age"), List.of(), "doris", 0));

        List<String> union = ColumnAligner.unionColumns(results);
        assertThat(union).containsExactly("id", "name", "age");
    }

    @Test
    @DisplayName("unionColumns — 空输入返回空列表")
    void unionColumns_emptyInput() {
        assertThat(ColumnAligner.unionColumns(null)).isEmpty();
        assertThat(ColumnAligner.unionColumns(List.of())).isEmpty();
    }

    // ===================== isCompatible =====================

    @Test
    @DisplayName("isCompatible — 完全匹配返回 true")
    void isCompatible_exactMatch() {
        assertThat(ColumnAligner.isCompatible(
                List.of("id", "name"),
                List.of("id", "name"))).isTrue();
    }

    @Test
    @DisplayName("isCompatible — 大小写不同但匹配返回 true")
    void isCompatible_caseInsensitive() {
        assertThat(ColumnAligner.isCompatible(
                List.of("id", "name"),
                List.of("ID", "NAME"))).isTrue();
    }

    @Test
    @DisplayName("isCompatible — 源缺少目标列返回 false")
    void isCompatible_missingColumn() {
        assertThat(ColumnAligner.isCompatible(
                List.of("id", "name", "age"),
                List.of("id", "name"))).isFalse();
    }

    @Test
    @DisplayName("isCompatible — 空目标列返回 true")
    void isCompatible_emptyTarget() {
        assertThat(ColumnAligner.isCompatible(
                List.of(),
                List.of("id"))).isTrue();
    }
}