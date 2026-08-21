package com.levango7.dataenginebdp.sqlgateway.crosssource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨源归并列对齐与类型转换集成测试。
 *
 * <p>验证 ROADMAP v1.1 修复项："SQL 网关跨源结果归并中的列对齐与类型转换问题"。
 * 覆盖以下真实跨源场景：</p>
 * <ul>
 *   <li>不同数据源返回列顺序不一致时 UNION 不再错位；</li>
 *   <li>不同数据源返回列名大小写不一致时 UNION 正确对齐；</li>
 *   <li>不同数据源返回 Java 类型不一致（Integer/Long/Double）时 UNION 去重正确；</li>
 *   <li>JOIN 列名大小写冲突检测；</li>
 *   <li>JOIN 跨源类型不一致的键匹配；</li>
 *   <li>NULL 值在 UNION/JOIN 中的处理。</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
class CrossSourceMergeAlignmentTest {

    // ===================== UNION 列对齐 =====================

    @Test
    @DisplayName("UNION ALL — 列顺序不一致时按列名对齐（不再错位）")
    void unionAll_differentColumnOrderAlignedByName() {
        // 源 A（Trino）: columns=[id, name], rows=[[1, "alice"]]
        MergeResult trinoResult = new MergeResult(
                List.of("id", "name"),
                List.of(List.of(1, "alice")),
                "trino", 0);
        // 源 B（Doris）: columns=[name, id], rows=[["bob", 2]]  ← 列顺序相反
        MergeResult dorisResult = new MergeResult(
                List.of("name", "id"),
                List.of(List.of("bob", 2)),
                "doris", 0);

        CrossSourceUnionEngine engine = new CrossSourceUnionEngine();
        MergeResult result = engine.union(
                List.of(trinoResult, dorisResult),
                CrossSourceUnionEngine.UnionType.UNION_ALL);

        // 输出列以第一个结果集为准
        assertThat(result.getColumns()).containsExactly("id", "name");
        assertThat(result.getRowCount()).isEqualTo(2);
        // 关键断言：Doris 行的 id=2 应在 id 列位置，name="bob" 应在 name 列位置
        // 修复前会按位置对齐导致 [id=alice, name=1] 错位
        assertThat(result.getRows().get(0)).containsExactly(BigDecimal.valueOf(1), "alice");
        assertThat(result.getRows().get(1)).containsExactly(BigDecimal.valueOf(2), "bob");
    }

    @Test
    @DisplayName("UNION ALL — 列名大小写不一致时按列名对齐")
    void unionAll_caseInsensitiveColumnNameAlignment() {
        // 源 A（Trino）: columns=[id, name]（小写）
        MergeResult trinoResult = new MergeResult(
                List.of("id", "name"),
                List.of(List.of(1, "alice")),
                "trino", 0);
        // 源 B（ES）: columns=[ID, NAME]（大写）
        MergeResult esResult = new MergeResult(
                List.of("ID", "NAME"),
                List.of(List.of(2, "bob")),
                "elasticsearch", 0);

        CrossSourceUnionEngine engine = new CrossSourceUnionEngine();
        MergeResult result = engine.union(
                List.of(trinoResult, esResult),
                CrossSourceUnionEngine.UnionType.UNION_ALL);

        assertThat(result.getRowCount()).isEqualTo(2);
        assertThat(result.getRows().get(0)).containsExactly(BigDecimal.valueOf(1), "alice");
        assertThat(result.getRows().get(1)).containsExactly(BigDecimal.valueOf(2), "bob");
    }

    @Test
    @DisplayName("UNION ALL — 源缺少目标列时填充 null")
    void unionAll_missingColumnFilledWithNull() {
        // 源 A: columns=[id, name, age]
        MergeResult trinoResult = new MergeResult(
                List.of("id", "name", "age"),
                List.of(List.of(1, "alice", 30)),
                "trino", 0);
        // 源 B: columns=[id, name]（缺少 age 列）
        MergeResult dorisResult = new MergeResult(
                List.of("id", "name"),
                List.of(List.of(2, "bob")),
                "doris", 0);

        CrossSourceUnionEngine engine = new CrossSourceUnionEngine();
        MergeResult result = engine.union(
                List.of(trinoResult, dorisResult),
                CrossSourceUnionEngine.UnionType.UNION_ALL);

        assertThat(result.getColumns()).containsExactly("id", "name", "age");
        assertThat(result.getRows().get(0)).containsExactly(
                BigDecimal.valueOf(1), "alice", BigDecimal.valueOf(30));
        // Doris 行的 age 列应为 null
        assertThat(result.getRows().get(1)).containsExactly(
                BigDecimal.valueOf(2), "bob", null);
    }

    // ===================== UNION 类型转换 =====================

    @Test
    @DisplayName("UNION DISTINCT — 不同数值类型（Integer/Long/Double）去重正确")
    void unionDistinct_differentNumericTypesDedup() {
        // 源 A 返回 Integer 1
        MergeResult trinoResult = new MergeResult(
                List.of("id"),
                List.of(List.of(1), List.of(2)),
                "trino", 0);
        // 源 B 返回 Long 1L 和 Double 2.0 — 语义上与源 A 的 1 和 2 相同
        MergeResult dorisResult = new MergeResult(
                List.of("id"),
                List.of(List.of(1L), List.of(2.0)),
                "doris", 0);

        CrossSourceUnionEngine engine = new CrossSourceUnionEngine();
        MergeResult result = engine.union(
                List.of(trinoResult, dorisResult),
                CrossSourceUnionEngine.UnionType.UNION_DISTINCT);

        // 修复前：Integer 1 与 Long 1L fingerprint 不同，会被视为不同行，结果 4 行
        // 修复后：类型归一化为 BigDecimal，1 与 1L 与 2.0 与 2 去重后只剩 2 行
        assertThat(result.getRowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("UNION ALL — 数值类型统一为 BigDecimal")
    void unionAll_numericTypeUnifiedToBigDecimal() {
        MergeResult trinoResult = new MergeResult(
                List.of("id"),
                List.of(List.of(1)),  // Integer
                "trino", 0);
        MergeResult dorisResult = new MergeResult(
                List.of("id"),
                List.of(List.of(2L)),  // Long
                "doris", 0);

        CrossSourceUnionEngine engine = new CrossSourceUnionEngine();
        MergeResult result = engine.union(
                List.of(trinoResult, dorisResult),
                CrossSourceUnionEngine.UnionType.UNION_ALL);

        // 所有数值统一为 BigDecimal，避免下游类型断言失败
        assertThat(result.getRows().get(0).get(0)).isInstanceOf(BigDecimal.class);
        assertThat(result.getRows().get(1).get(0)).isInstanceOf(BigDecimal.class);
    }

    @Test
    @DisplayName("UNION ALL — 字符串去首尾空格")
    void unionAll_stringTrimmed() {
        MergeResult trinoResult = new MergeResult(
                List.of("name"),
                List.of(List.of("  alice  ")),
                "trino", 0);

        CrossSourceUnionEngine engine = new CrossSourceUnionEngine();
        MergeResult result = engine.union(
                List.of(trinoResult),
                CrossSourceUnionEngine.UnionType.UNION_ALL);

        assertThat(result.getRows().get(0).get(0)).isEqualTo("alice");
    }

    // ===================== UNION NULL 处理 =====================

    @Test
    @DisplayName("UNION DISTINCT — NULL 值参与去重")
    void unionDistinct_nullValueDedup() {
        MergeResult trinoResult = new MergeResult(
                List.of("id", "name"),
                List.of(Arrays.asList(1, null), List.of(2, "bob")),
                "trino", 0);
        MergeResult dorisResult = new MergeResult(
                List.of("id", "name"),
                List.of(Arrays.asList(1, null)),  // 与 trino 第一行语义相同
                "doris", 0);

        CrossSourceUnionEngine engine = new CrossSourceUnionEngine();
        MergeResult result = engine.union(
                List.of(trinoResult, dorisResult),
                CrossSourceUnionEngine.UnionType.UNION_DISTINCT);

        // [1, null] 应去重，最终 2 行
        assertThat(result.getRowCount()).isEqualTo(2);
    }

    // ===================== JOIN 列名冲突检测 =====================

    @Test
    @DisplayName("Hash Join — 列名大小写冲突时右列追加 _right 后缀")
    void hashJoin_caseInsensitiveColumnNameConflict() {
        // 左表列名大写 ID，右表列名小写 id — 语义冲突
        MergeResult left = new MergeResult(
                List.of("ID", "value"),
                List.of(List.of(1, "L1")),
                "trino", 0);
        MergeResult right = new MergeResult(
                List.of("id", "value"),
                List.of(List.of(1, "R1")),
                "doris", 0);

        CrossSourceJoinEngine engine = new CrossSourceJoinEngine();
        MergeResult result = engine.hashJoin(left, right,
                new CrossSourceJoinEngine.JoinCondition("ID", "id"));

        // 修复前：combineColumns 用大小写敏感的 contains，ID 与 id 不冲突，结果列含 ID 和 id 两列
        // 修复后：大小写无关检测，id 和 value 都冲突，追加 _right 后缀
        assertThat(result.getColumns()).containsExactly("ID", "value", "id_right", "value_right");
    }

    // ===================== JOIN 跨源类型不一致 =====================

    @Test
    @DisplayName("Hash Join — 跨源 JOIN 键类型不一致（Integer vs Long）能匹配")
    void hashJoin_crossSourceTypeMismatch() {
        // Trino 返回 Integer 键
        MergeResult left = new MergeResult(
                List.of("id", "name"),
                List.of(List.of(1, "alice"), List.of(2, "bob")),
                "trino", 0);
        // Doris 返回 Long 键
        MergeResult right = new MergeResult(
                List.of("uid", "age"),
                List.of(List.of(1L, 30), List.of(3L, 40)),
                "doris", 0);

        CrossSourceJoinEngine engine = new CrossSourceJoinEngine();
        MergeResult result = engine.hashJoin(left, right,
                new CrossSourceJoinEngine.JoinCondition("id", "uid"));

        // 修复前：Integer 1 与 Long 1L 在 equals 比较下不相等，匹配失败，结果 0 行
        // 修复后：类型归一化为 BigDecimal，1 与 1L 匹配，结果 1 行
        assertThat(result.getRowCount()).isEqualTo(1);
        assertThat(result.getRows().get(0).get(0)).isInstanceOf(BigDecimal.class);
        assertThat(result.getRows().get(0).get(2)).isInstanceOf(BigDecimal.class);
    }

    @Test
    @DisplayName("Hash Join — 字符串键大小写无关匹配")
    void hashJoin_stringKeyCaseInsensitive() {
        MergeResult left = new MergeResult(
                List.of("code", "name"),
                List.of(List.of("ABC", "alice")),
                "trino", 0);
        MergeResult right = new MergeResult(
                List.of("code", "age"),
                List.of(List.of("abc", 30)),
                "doris", 0);

        CrossSourceJoinEngine engine = new CrossSourceJoinEngine();
        MergeResult result = engine.hashJoin(left, right,
                new CrossSourceJoinEngine.JoinCondition("code", "code"));

        assertThat(result.getRowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Hash Join — 输出行类型归一化为 BigDecimal")
    void hashJoin_outputRowTypeUnified() {
        MergeResult left = new MergeResult(
                List.of("id", "name"),
                List.of(List.of(1, "alice")),
                "trino", 0);
        MergeResult right = new MergeResult(
                List.of("id", "age"),
                List.of(List.of(1, 30)),
                "doris", 0);

        CrossSourceJoinEngine engine = new CrossSourceJoinEngine();
        MergeResult result = engine.hashJoin(left, right,
                new CrossSourceJoinEngine.JoinCondition("id", "id"));

        // 输出行数值统一为 BigDecimal
        assertThat(result.getRows().get(0).get(0)).isInstanceOf(BigDecimal.class);
        assertThat(result.getRows().get(0).get(2)).isInstanceOf(BigDecimal.class);
        assertThat(result.getRows().get(0).get(3)).isInstanceOf(BigDecimal.class);
    }

    // ===================== JOIN NULL 处理 =====================

    @Test
    @DisplayName("Hash Join — JOIN 键为 null 时不匹配（SQL 语义）")
    void hashJoin_nullKeyNoMatch() {
        MergeResult left = new MergeResult(
                List.of("id", "name"),
                List.of(Arrays.asList(null, "alice")),
                "trino", 0);
        MergeResult right = new MergeResult(
                List.of("id", "age"),
                List.of(Arrays.asList(null, 30)),
                "doris", 0);

        CrossSourceJoinEngine engine = new CrossSourceJoinEngine();
        MergeResult result = engine.hashJoin(left, right,
                new CrossSourceJoinEngine.JoinCondition("id", "id"));

        // SQL 语义：NULL = NULL 为 UNKNOWN，不匹配
        assertThat(result.getRowCount()).isZero();
    }

    // ===================== Sort-Merge Join 类型统一 =====================

    @Test
    @DisplayName("Sort-Merge Join — 跨源类型不一致的键能匹配")
    void mergeJoin_crossSourceTypeMismatch() {
        MergeResult left = new MergeResult(
                List.of("id", "name"),
                List.of(List.of(1, "alice"), List.of(2, "bob")),
                "trino", 0);
        MergeResult right = new MergeResult(
                List.of("id", "age"),
                List.of(List.of(1L, 30), List.of(2L, 25)),
                "doris", 0);

        CrossSourceJoinEngine engine = new CrossSourceJoinEngine();
        MergeResult result = engine.mergeJoin(left, right,
                new CrossSourceJoinEngine.JoinCondition("id", "id"));

        // 修复后：Integer 与 Long 归一化为 BigDecimal 后能匹配
        assertThat(result.getRowCount()).isEqualTo(2);
    }

    // ===================== Nested Loop Join 类型统一 =====================

    @Test
    @DisplayName("Nested Loop Join — 跨源类型不一致的比较能正确执行")
    void nestedLoopJoin_crossSourceTypeComparison() {
        MergeResult left = new MergeResult(
                List.of("a"),
                List.of(List.of(1), List.of(5)),
                "trino", 0);
        MergeResult right = new MergeResult(
                List.of("b"),
                List.of(List.of(3L), List.of(10L)),
                "doris", 0);

        CrossSourceJoinEngine engine = new CrossSourceJoinEngine();
        // a < b: (1,3),(1,10),(5,10) = 3 行
        MergeResult result = engine.nestedLoopJoin(left, right,
                new CrossSourceJoinEngine.JoinCondition("a", "b", "<"));

        assertThat(result.getRowCount()).isEqualTo(3);
    }
}