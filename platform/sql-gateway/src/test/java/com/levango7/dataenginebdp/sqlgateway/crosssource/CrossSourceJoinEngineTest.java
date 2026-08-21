package com.levango7.dataenginebdp.sqlgateway.crosssource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CrossSourceJoinEngine} 单元测试。
 *
 * <p>覆盖 Hash Join、Nested Loop Join、Sort-Merge Join 三种算法，
 * 以及等值/不等值 JOIN、列名冲突、结果集上限、异常输入等场景。</p>
 *
 * @author shuqing-bigdata
 */
class CrossSourceJoinEngineTest {

    // ===================== Hash Join =====================

    @Test
    @DisplayName("Hash Join — 等值 JOIN 基本场景")
    void hashJoin_equalBasic() {
        MergeResult left = new MergeResult(
                List.of("id", "name"),
                List.of(
                        List.of(1, "alice"),
                        List.of(2, "bob"),
                        List.of(3, "carol")),
                "trino", 0);
        MergeResult right = new MergeResult(
                List.of("uid", "age"),
                List.of(
                        List.of(1, 30),
                        List.of(2, 25),
                        List.of(4, 40)),
                "doris", 0);

        CrossSourceJoinEngine engine = new CrossSourceJoinEngine();
        MergeResult result = engine.hashJoin(left, right,
                new CrossSourceJoinEngine.JoinCondition("id", "uid"));

        assertThat(result.getRowCount()).isEqualTo(2);
        assertThat(result.getColumns()).containsExactly("id", "name", "uid", "age");
        // 第一行：id=1, name=alice, uid=1, age=30（数值经类型归一化为 BigDecimal）
        assertThat(result.getRows().get(0))
                .containsExactly(BigDecimal.valueOf(1), "alice", BigDecimal.valueOf(1), BigDecimal.valueOf(30));
        // 第二行：id=2, name=bob, uid=2, age=25
        assertThat(result.getRows().get(1))
                .containsExactly(BigDecimal.valueOf(2), "bob", BigDecimal.valueOf(2), BigDecimal.valueOf(25));
        assertThat(result.getSource()).isEqualTo("merged");
    }

    @Test
    @DisplayName("Hash Join — 自动选择小表作为 build side")
    void hashJoin_smallTableAsBuild() {
        // 左表 100 行，右表 3 行 → 应选右表作为 build side
        List<List<Object>> leftRows = new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            leftRows.add(List.of(i, "name" + i));
        }
        MergeResult left = new MergeResult(List.of("id", "name"), leftRows, "trino", 0);

        MergeResult right = new MergeResult(
                List.of("id", "score"),
                List.of(List.of(50, 95), List.of(60, 88), List.of(70, 76)),
                "doris", 0);

        CrossSourceJoinEngine engine = new CrossSourceJoinEngine();
        MergeResult result = engine.hashJoin(left, right,
                new CrossSourceJoinEngine.JoinCondition("id", "id"));

        assertThat(result.getRowCount()).isEqualTo(3);
        // 列顺序：左 + 右，但右表也有 id 列 → 应重命名为 id_right
        assertThat(result.getColumns()).containsExactly("id", "name", "id_right", "score");
    }

    @Test
    @DisplayName("Hash Join — 列名冲突时右列追加 _right 后缀")
    void hashJoin_columnNameConflict() {
        MergeResult left = new MergeResult(
                List.of("id", "value"),
                List.of(List.of(1, "L1")),
                "trino", 0);
        MergeResult right = new MergeResult(
                List.of("id", "value"),
                List.of(List.of(1, "R1")),
                "doris", 0);

        CrossSourceJoinEngine engine = new CrossSourceJoinEngine();
        MergeResult result = engine.hashJoin(left, right,
                new CrossSourceJoinEngine.JoinCondition("id", "id"));

        assertThat(result.getColumns()).containsExactly("id", "value", "id_right", "value_right");
        assertThat(result.getRows().get(0))
                .containsExactly(BigDecimal.valueOf(1), "L1", BigDecimal.valueOf(1), "R1");
    }

    @Test
    @DisplayName("Hash Join — 字符串键匹配（大小写无关、去空格）")
    void hashJoin_stringKeyNormalized() {
        MergeResult left = new MergeResult(
                List.of("code", "name"),
                List.of(List.of(" ABC ", "alice")),
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
    @DisplayName("Hash Join — 无匹配时返回空结果")
    void hashJoin_noMatch() {
        MergeResult left = new MergeResult(
                List.of("id", "name"),
                List.of(List.of(1, "alice")),
                "trino", 0);
        MergeResult right = new MergeResult(
                List.of("id", "age"),
                List.of(List.of(2, 30)),
                "doris", 0);

        CrossSourceJoinEngine engine = new CrossSourceJoinEngine();
        MergeResult result = engine.hashJoin(left, right,
                new CrossSourceJoinEngine.JoinCondition("id", "id"));

        assertThat(result.getRowCount()).isEqualTo(0);
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("Hash Join — 一对多关系（一侧键重复）")
    void hashJoin_oneToMany() {
        MergeResult left = new MergeResult(
                List.of("id", "name"),
                List.of(List.of(1, "alice")),
                "trino", 0);
        MergeResult right = new MergeResult(
                List.of("id", "order"),
                List.of(List.of(1, "A"), List.of(1, "B"), List.of(1, "C")),
                "doris", 0);

        CrossSourceJoinEngine engine = new CrossSourceJoinEngine();
        MergeResult result = engine.hashJoin(left, right,
                new CrossSourceJoinEngine.JoinCondition("id", "id"));

        assertThat(result.getRowCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Hash Join — 非等值操作符回退到 Nested Loop")
    void hashJoin_nonEqualFallsBackToNestedLoop() {
        MergeResult left = new MergeResult(
                List.of("a"),
                List.of(List.of(1), List.of(5)),
                "trino", 0);
        MergeResult right = new MergeResult(
                List.of("b"),
                List.of(List.of(3), List.of(10)),
                "doris", 0);

        CrossSourceJoinEngine engine = new CrossSourceJoinEngine();
        // a < b: (1,3), (1,10), (5,10)
        MergeResult result = engine.hashJoin(left, right,
                new CrossSourceJoinEngine.JoinCondition("a", "b", "<"));

        assertThat(result.getRowCount()).isEqualTo(3);
    }

    // ===================== Nested Loop Join =====================

    @Test
    @DisplayName("Nested Loop Join — 小于操作符")
    void nestedLoopJoin_lessThan() {
        MergeResult left = new MergeResult(
                List.of("a"),
                List.of(List.of(1), List.of(5), List.of(8)),
                "trino", 0);
        MergeResult right = new MergeResult(
                List.of("b"),
                List.of(List.of(3), List.of(10)),
                "doris", 0);

        CrossSourceJoinEngine engine = new CrossSourceJoinEngine();
        // a < b: (1,3),(1,10),(5,3)? no, 5<3 false, (5,10),(8,10)
        MergeResult result = engine.nestedLoopJoin(left, right,
                new CrossSourceJoinEngine.JoinCondition("a", "b", "<"));

        // (1,3),(1,10),(5,10),(8,10) = 4 行
        assertThat(result.getRowCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("Nested Loop Join — 不等操作符 (!=)")
    void nestedLoopJoin_notEqual() {
        MergeResult left = new MergeResult(
                List.of("a"),
                List.of(List.of(1), List.of(2)),
                "trino", 0);
        MergeResult right = new MergeResult(
                List.of("b"),
                List.of(List.of(1), List.of(2)),
                "doris", 0);

        CrossSourceJoinEngine engine = new CrossSourceJoinEngine();
        // a != b: (1,2),(2,1) = 2 行
        MergeResult result = engine.nestedLoopJoin(left, right,
                new CrossSourceJoinEngine.JoinCondition("a", "b", "!="));

        assertThat(result.getRowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Nested Loop Join — 大于等于操作符 (>=)")
    void nestedLoopJoin_greaterEqual() {
        MergeResult left = new MergeResult(
                List.of("a"),
                List.of(List.of(5)),
                "trino", 0);
        MergeResult right = new MergeResult(
                List.of("b"),
                List.of(List.of(3), List.of(5), List.of(7)),
                "doris", 0);

        CrossSourceJoinEngine engine = new CrossSourceJoinEngine();
        // 5 >= 3, 5 >= 5 → 2 行（5 >= 7 false）
        MergeResult result = engine.nestedLoopJoin(left, right,
                new CrossSourceJoinEngine.JoinCondition("a", "b", ">="));

        assertThat(result.getRowCount()).isEqualTo(2);
    }

    // ===================== Sort-Merge Join =====================

    @Test
    @DisplayName("Sort-Merge Join — 已排序等值 JOIN")
    void mergeJoin_sortedEqual() {
        MergeResult left = new MergeResult(
                List.of("id", "name"),
                List.of(
                        List.of(1, "alice"),
                        List.of(2, "bob"),
                        List.of(3, "carol")),
                "trino", 0);
        MergeResult right = new MergeResult(
                List.of("id", "age"),
                List.of(
                        List.of(1, 30),
                        List.of(2, 25),
                        List.of(3, 28)),
                "doris", 0);

        CrossSourceJoinEngine engine = new CrossSourceJoinEngine();
        MergeResult result = engine.mergeJoin(left, right,
                new CrossSourceJoinEngine.JoinCondition("id", "id"));

        assertThat(result.getRowCount()).isEqualTo(3);
        assertThat(result.getRows().get(0))
                .containsExactly(BigDecimal.valueOf(1), "alice", BigDecimal.valueOf(1), BigDecimal.valueOf(30));
    }

    @Test
    @DisplayName("Sort-Merge Join — 非等值操作符抛 UNSUPPORTED")
    void mergeJoin_nonEqualThrowsUnsupported() {
        MergeResult left = new MergeResult(
                List.of("a"), List.of(List.of(1)), "trino", 0);
        MergeResult right = new MergeResult(
                List.of("b"), List.of(List.of(1)), "doris", 0);

        CrossSourceJoinEngine engine = new CrossSourceJoinEngine();
        assertThatThrownBy(() -> engine.mergeJoin(left, right,
                new CrossSourceJoinEngine.JoinCondition("a", "b", "<")))
                .isInstanceOf(CrossSourceException.class)
                .satisfies(e -> assertThat(((CrossSourceException) e).getErrorCode())
                        .isEqualTo(CrossSourceException.UNSUPPORTED));
    }

    // ===================== 异常场景 =====================

    @Test
    @DisplayName("Hash Join — 找不到 JOIN 键列抛 MERGE_ERROR")
    void hashJoin_columnNotFound() {
        MergeResult left = new MergeResult(
                List.of("id"), List.of(List.of(1)), "trino", 0);
        MergeResult right = new MergeResult(
                List.of("uid"), List.of(List.of(1)), "doris", 0);

        CrossSourceJoinEngine engine = new CrossSourceJoinEngine();
        assertThatThrownBy(() -> engine.hashJoin(left, right,
                new CrossSourceJoinEngine.JoinCondition("nonexistent", "uid")))
                .isInstanceOf(CrossSourceException.class)
                .satisfies(e -> assertThat(((CrossSourceException) e).getErrorCode())
                        .isEqualTo(CrossSourceException.MERGE_ERROR));
    }

    @Test
    @DisplayName("Hash Join — 不支持的操作符抛 UNSUPPORTED")
    void hashJoin_unsupportedOperator() {
        MergeResult left = new MergeResult(
                List.of("id"), List.of(List.of(1)), "trino", 0);
        MergeResult right = new MergeResult(
                List.of("id"), List.of(List.of(1)), "doris", 0);

        CrossSourceJoinEngine engine = new CrossSourceJoinEngine();
        assertThatThrownBy(() -> engine.hashJoin(left, right,
                new CrossSourceJoinEngine.JoinCondition("id", "id", "LIKE")))
                .isInstanceOf(CrossSourceException.class)
                .satisfies(e -> assertThat(((CrossSourceException) e).getErrorCode())
                        .isEqualTo(CrossSourceException.UNSUPPORTED));
    }

    @Test
    @DisplayName("Hash Join — 结果超过上限抛 RESULT_TOO_LARGE")
    void hashJoin_resultTooLarge() {
        // 左表 10 行，右表 10 行，全部匹配 → 100 行
        List<List<Object>> leftRows = new java.util.ArrayList<>();
        List<List<Object>> rightRows = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            leftRows.add(List.of(1, "L" + i));
            rightRows.add(List.of(1, "R" + i));
        }
        MergeResult left = new MergeResult(List.of("id", "name"), leftRows, "trino", 0);
        MergeResult right = new MergeResult(List.of("id", "value"), rightRows, "doris", 0);

        CrossSourceJoinEngine engine = new CrossSourceJoinEngine(50);
        assertThatThrownBy(() -> engine.hashJoin(left, right,
                new CrossSourceJoinEngine.JoinCondition("id", "id")))
                .isInstanceOf(CrossSourceException.class)
                .satisfies(e -> assertThat(((CrossSourceException) e).getErrorCode())
                        .isEqualTo(CrossSourceException.RESULT_TOO_LARGE));
    }

    @Test
    @DisplayName("Hash Join — null 输入抛 MERGE_ERROR")
    void hashJoin_nullInput() {
        CrossSourceJoinEngine engine = new CrossSourceJoinEngine();
        assertThatThrownBy(() -> engine.hashJoin(null, null, null))
                .isInstanceOf(CrossSourceException.class);
    }

    @Test
    @DisplayName("supportedOperators — 返回 6 个操作符")
    void supportedOperators_returnsAll() {
        List<String> ops = CrossSourceJoinEngine.supportedOperators();
        assertThat(ops).containsExactly("=", "!=", "<", ">", "<=", ">=");
    }
}