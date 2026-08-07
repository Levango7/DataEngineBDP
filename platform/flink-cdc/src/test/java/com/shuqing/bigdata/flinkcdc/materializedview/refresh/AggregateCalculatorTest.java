package com.shuqing.bigdata.flinkcdc.materializedview.refresh;

import com.shuqing.bigdata.flinkcdc.materializedview.model.AggregationType;
import com.shuqing.bigdata.flinkcdc.model.ChangeRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AggregateCalculator} 单元测试。
 *
 * @author shuqing-bigdata
 */
class AggregateCalculatorTest {

    private ChangeRecord insert(Map<String, Object> after, String db, String table) {
        Map<String, Object> source = Map.of("db", db, "table", table);
        return new ChangeRecord(null, after, "c", source, System.currentTimeMillis());
    }

    private ChangeRecord delete(Map<String, Object> before, String db, String table) {
        Map<String, Object> source = Map.of("db", db, "table", table);
        return new ChangeRecord(before, null, "d", source, System.currentTimeMillis());
    }

    private ChangeRecord update(Map<String, Object> before, Map<String, Object> after, String db, String table) {
        Map<String, Object> source = Map.of("db", db, "table", table);
        return new ChangeRecord(before, after, "u", source, System.currentTimeMillis());
    }

    @Nested
    @DisplayName("COUNT 聚合")
    class CountTest {

        @Test
        @DisplayName("INSERT — 计数累加")
        void count_insert() {
            AggregateCalculator calc = new AggregateCalculator(
                    List.of("region"), Map.of("cnt", AggregationType.COUNT));
            calc.apply(insert(Map.of("region", "east", "cnt", 1), "d", "t"));
            calc.apply(insert(Map.of("region", "east", "cnt", 1), "d", "t"));
            calc.apply(insert(Map.of("region", "west", "cnt", 1), "d", "t"));
            Map<String, Map<String, Number>> snapshot = calc.snapshot();
            assertThat(snapshot.get("east").get("cnt")).isEqualTo(2L);
            assertThat(snapshot.get("west").get("cnt")).isEqualTo(1L);
        }

        @Test
        @DisplayName("DELETE — 计数减少")
        void count_delete() {
            AggregateCalculator calc = new AggregateCalculator(
                    List.of("region"), Map.of("cnt", AggregationType.COUNT));
            calc.apply(insert(Map.of("region", "east", "cnt", 1), "d", "t"));
            calc.apply(insert(Map.of("region", "east", "cnt", 1), "d", "t"));
            calc.apply(delete(Map.of("region", "east", "cnt", 1), "d", "t"));
            assertThat(calc.snapshot().get("east").get("cnt")).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("SUM 聚合")
    class SumTest {

        @Test
        @DisplayName("INSERT — 求和累加")
        void sum_insert() {
            AggregateCalculator calc = new AggregateCalculator(
                    List.of("region"), Map.of("total", AggregationType.SUM));
            calc.apply(insert(Map.of("region", "east", "total", 100), "d", "t"));
            calc.apply(insert(Map.of("region", "east", "total", 200), "d", "t"));
            calc.apply(insert(Map.of("region", "west", "total", 50), "d", "t"));
            Map<String, Map<String, Number>> snapshot = calc.snapshot();
            assertThat(snapshot.get("east").get("total").doubleValue()).isEqualTo(300.0);
            assertThat(snapshot.get("west").get("total").doubleValue()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("UPDATE — 先减后加")
        void sum_update() {
            AggregateCalculator calc = new AggregateCalculator(
                    List.of("region"), Map.of("total", AggregationType.SUM));
            calc.apply(insert(Map.of("region", "east", "total", 100), "d", "t"));
            calc.apply(update(
                    Map.of("region", "east", "total", 100),
                    Map.of("region", "east", "total", 150), "d", "t"));
            assertThat(calc.snapshot().get("east").get("total").doubleValue()).isEqualTo(150.0);
        }
    }

    @Nested
    @DisplayName("AVG 聚合")
    class AvgTest {

        @Test
        @DisplayName("INSERT — 计算平均值")
        void avg_insert() {
            AggregateCalculator calc = new AggregateCalculator(
                    List.of("region"), Map.of("avg_price", AggregationType.AVG));
            calc.apply(insert(Map.of("region", "east", "avg_price", 100), "d", "t"));
            calc.apply(insert(Map.of("region", "east", "avg_price", 200), "d", "t"));
            calc.apply(insert(Map.of("region", "east", "avg_price", 300), "d", "t"));
            assertThat(calc.snapshot().get("east").get("avg_price").doubleValue()).isEqualTo(200.0);
        }

        @Test
        @DisplayName("无数据 — 返回 null")
        void avg_empty() {
            AggregateCalculator calc = new AggregateCalculator(
                    List.of("region"), Map.of("avg_price", AggregationType.AVG));
            calc.apply(insert(Map.of("region", "east", "avg_price", 100), "d", "t"));
            calc.apply(delete(Map.of("region", "east", "avg_price", 100), "d", "t"));
            // count=0，应返回 null
            Number value = calc.snapshot().get("east").get("avg_price");
            assertThat(value).isNull();
        }
    }

    @Nested
    @DisplayName("MIN/MAX 聚合")
    class MinMaxTest {

        @Test
        @DisplayName("MIN — 取最小值")
        void min_insert() {
            AggregateCalculator calc = new AggregateCalculator(
                    List.of("region"), Map.of("min_price", AggregationType.MIN));
            calc.apply(insert(Map.of("region", "east", "min_price", 100), "d", "t"));
            calc.apply(insert(Map.of("region", "east", "min_price", 50), "d", "t"));
            calc.apply(insert(Map.of("region", "east", "min_price", 200), "d", "t"));
            assertThat(calc.snapshot().get("east").get("min_price").doubleValue()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("MAX — 取最大值")
        void max_insert() {
            AggregateCalculator calc = new AggregateCalculator(
                    List.of("region"), Map.of("max_price", AggregationType.MAX));
            calc.apply(insert(Map.of("region", "east", "max_price", 100), "d", "t"));
            calc.apply(insert(Map.of("region", "east", "max_price", 300), "d", "t"));
            calc.apply(insert(Map.of("region", "east", "max_price", 200), "d", "t"));
            assertThat(calc.snapshot().get("east").get("max_price").doubleValue()).isEqualTo(300.0);
        }

        @Test
        @DisplayName("MIN DELETE — 标记为需全量重算")
        void min_delete() {
            AggregateCalculator calc = new AggregateCalculator(
                    List.of("region"), Map.of("min_price", AggregationType.MIN));
            calc.apply(insert(Map.of("region", "east", "min_price", 100), "d", "t"));
            calc.apply(insert(Map.of("region", "east", "min_price", 50), "d", "t"));
            calc.apply(delete(Map.of("region", "east", "min_price", 50), "d", "t"));
            // DELETE 后 MIN 标记为 NaN，返回 null
            assertThat(calc.snapshot().get("east").get("min_price")).isNull();
        }
    }

    @Nested
    @DisplayName("多指标组合")
    class MultiMetricTest {

        @Test
        @DisplayName("COUNT + SUM + AVG 同时计算")
        void multiMetric() {
            Map<String, AggregationType> metrics = new LinkedHashMap<>();
            metrics.put("cnt", AggregationType.COUNT);
            metrics.put("total", AggregationType.SUM);
            metrics.put("avg", AggregationType.AVG);
            AggregateCalculator calc = new AggregateCalculator(List.of("region"), metrics);
            calc.apply(insert(Map.of("region", "east", "total", 100, "avg", 100), "d", "t"));
            calc.apply(insert(Map.of("region", "east", "total", 300, "avg", 300), "d", "t"));
            Map<String, Number> result = calc.snapshot().get("east");
            assertThat(result.get("cnt")).isEqualTo(2L);
            assertThat(result.get("total").doubleValue()).isEqualTo(400.0);
            assertThat(result.get("avg").doubleValue()).isEqualTo(200.0);
        }
    }

    @Nested
    @DisplayName("snapshotWithDimensions — 带维度解析")
    class SnapshotWithDimensionsTest {

        @Test
        @DisplayName("返回维度列 + 指标列")
        void snapshotWithDimensions() {
            AggregateCalculator calc = new AggregateCalculator(
                    List.of("region"), Map.of("total", AggregationType.SUM));
            calc.apply(insert(Map.of("region", "east", "total", 100), "d", "t"));
            calc.apply(insert(Map.of("region", "west", "total", 200), "d", "t"));
            List<Map<String, Object>> rows = calc.snapshotWithDimensions();
            assertThat(rows).hasSize(2);
            assertThat(rows).anyMatch(row -> "east".equals(row.get("region")) && ((Number) row.get("total")).doubleValue() == 100.0);
            assertThat(rows).anyMatch(row -> "west".equals(row.get("region")) && ((Number) row.get("total")).doubleValue() == 200.0);
        }
    }

    @Nested
    @DisplayName("辅助方法")
    class HelperTest {

        @Test
        @DisplayName("reset — 清空状态")
        void reset() {
            AggregateCalculator calc = new AggregateCalculator(
                    List.of("region"), Map.of("total", AggregationType.SUM));
            calc.apply(insert(Map.of("region", "east", "total", 100), "d", "t"));
            assertThat(calc.dimensionCount()).isEqualTo(1);
            calc.reset();
            assertThat(calc.dimensionCount()).isZero();
            assertThat(calc.snapshot()).isEmpty();
        }

        @Test
        @DisplayName("无维度 — 使用 __ALL__ key")
        void noDimensions() {
            AggregateCalculator calc = new AggregateCalculator(
                    List.of(), Map.of("total", AggregationType.SUM));
            calc.apply(insert(Map.of("total", 100), "d", "t"));
            calc.apply(insert(Map.of("total", 200), "d", "t"));
            assertThat(calc.snapshot()).containsKey("__ALL__");
            assertThat(calc.snapshot().get("__ALL__").get("total").doubleValue()).isEqualTo(300.0);
        }

        @Test
        @DisplayName("numericValue — 非数值返回 0")
        void numericValue_nonNumeric() {
            Map<String, Object> row = Map.of("col", "not-a-number");
            assertThat(AggregateCalculator.numericValue(row, "col")).isZero();
            assertThat(AggregateCalculator.numericValue(row, "missing")).isZero();
        }

        @Test
        @DisplayName("numericValue — 字符串数值可解析")
        void numericValue_stringNumeric() {
            Map<String, Object> row = Map.of("col", "123.45");
            assertThat(AggregateCalculator.numericValue(row, "col")).isEqualTo(123.45);
        }
    }
}