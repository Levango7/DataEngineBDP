package com.shuqing.bigdata.flinkcdc.materializedview.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AggregationType} 单元测试。
 *
 * @author shuqing-bigdata
 */
class AggregationTypeTest {

    @Nested
    @DisplayName("fromName — 解析聚合类型")
    class FromNameTest {

        @Test
        @DisplayName("正确解析所有类型（大写）")
        void fromName_uppercase() {
            assertThat(AggregationType.fromName("COUNT")).isEqualTo(AggregationType.COUNT);
            assertThat(AggregationType.fromName("SUM")).isEqualTo(AggregationType.SUM);
            assertThat(AggregationType.fromName("AVG")).isEqualTo(AggregationType.AVG);
            assertThat(AggregationType.fromName("MIN")).isEqualTo(AggregationType.MIN);
            assertThat(AggregationType.fromName("MAX")).isEqualTo(AggregationType.MAX);
        }

        @Test
        @DisplayName("大小写不敏感")
        void fromName_caseInsensitive() {
            assertThat(AggregationType.fromName("count")).isEqualTo(AggregationType.COUNT);
            assertThat(AggregationType.fromName("Sum")).isEqualTo(AggregationType.SUM);
            assertThat(AggregationType.fromName("avg")).isEqualTo(AggregationType.AVG);
        }

        @Test
        @DisplayName("去除前后空格")
        void fromName_trim() {
            assertThat(AggregationType.fromName("  COUNT  ")).isEqualTo(AggregationType.COUNT);
        }

        @Test
        @DisplayName("null 抛出 NPE")
        void fromName_null_throwsNpe() {
            assertThatThrownBy(() -> AggregationType.fromName(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("未知类型抛出异常")
        void fromName_unknown_throws() {
            assertThatThrownBy(() -> AggregationType.fromName("MEDIAN"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("属性查询")
    class PropertyTest {

        @Test
        @DisplayName("sqlFunction — 返回正确函数名")
        void sqlFunction() {
            assertThat(AggregationType.COUNT.sqlFunction()).isEqualTo("COUNT");
            assertThat(AggregationType.SUM.sqlFunction()).isEqualTo("SUM");
            assertThat(AggregationType.AVG.sqlFunction()).isEqualTo("AVG");
        }

        @Test
        @DisplayName("isIncrementalMergeable — COUNT/SUM/AVG 可增量合并")
        void incrementalMergeable() {
            assertThat(AggregationType.COUNT.isIncrementalMergeable()).isTrue();
            assertThat(AggregationType.SUM.isIncrementalMergeable()).isTrue();
            assertThat(AggregationType.AVG.isIncrementalMergeable()).isTrue();
            assertThat(AggregationType.MIN.isIncrementalMergeable()).isFalse();
            assertThat(AggregationType.MAX.isIncrementalMergeable()).isFalse();
        }

        @Test
        @DisplayName("requiresAuxState — 仅 AVG 需辅助状态")
        void requiresAuxState() {
            assertThat(AggregationType.AVG.requiresAuxState()).isTrue();
            assertThat(AggregationType.COUNT.requiresAuxState()).isFalse();
            assertThat(AggregationType.SUM.requiresAuxState()).isFalse();
            assertThat(AggregationType.MIN.requiresAuxState()).isFalse();
            assertThat(AggregationType.MAX.requiresAuxState()).isFalse();
        }

        @Test
        @DisplayName("description — 返回中文描述")
        void description() {
            assertThat(AggregationType.COUNT.description()).isEqualTo("计数");
            assertThat(AggregationType.SUM.description()).isEqualTo("求和");
            assertThat(AggregationType.AVG.description()).isEqualTo("平均值");
            assertThat(AggregationType.MIN.description()).isEqualTo("最小值");
            assertThat(AggregationType.MAX.description()).isEqualTo("最大值");
        }
    }

    @Nested
    @DisplayName("sqlExpression — SQL 表达式生成")
    class SqlExpressionTest {

        @Test
        @DisplayName("COUNT — 生成 COUNT(*)")
        void countExpression() {
            assertThat(AggregationType.COUNT.sqlExpression("amount")).isEqualTo("COUNT(*)");
            assertThat(AggregationType.COUNT.sqlExpression(null)).isEqualTo("COUNT(*)");
        }

        @Test
        @DisplayName("SUM — 生成 SUM(col)")
        void sumExpression() {
            assertThat(AggregationType.SUM.sqlExpression("amount")).isEqualTo("SUM(amount)");
        }

        @Test
        @DisplayName("AVG — 生成 AVG(col)")
        void avgExpression() {
            assertThat(AggregationType.AVG.sqlExpression("price")).isEqualTo("AVG(price)");
        }

        @Test
        @DisplayName("MIN/MAX — 生成 MIN(col)/MAX(col)")
        void minMaxExpression() {
            assertThat(AggregationType.MIN.sqlExpression("price")).isEqualTo("MIN(price)");
            assertThat(AggregationType.MAX.sqlExpression("price")).isEqualTo("MAX(price)");
        }

        @Test
        @DisplayName("非 COUNT 类型 — 列名为 null 抛出 NPE")
        void nullColumn_throws() {
            assertThatThrownBy(() -> AggregationType.SUM.sqlExpression(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}