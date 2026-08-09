package com.levango7.dataenginebdp.flinkcdc.materializedview.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MaterializedViewDef} 单元测试。
 *
 * @author shuqing-bigdata
 */
class MaterializedViewDefTest {

    private MaterializedViewDef sampleView() {
        return MaterializedViewDef.builder()
                .name("mv_order_summary")
                .database("report")
                .targetTable("mv_order_summary")
                .addSourceTable("shop.orders")
                .addDimension("order_date")
                .addDimension("region")
                .addMetric("total_amount", AggregationType.SUM)
                .addMetric("order_count", AggregationType.COUNT)
                .refreshPolicy(RefreshPolicy.scheduled(Duration.ofMinutes(5)))
                .build();
    }

    @Nested
    @DisplayName("Builder — 链式构造")
    class BuilderTest {

        @Test
        @DisplayName("全字段构造 — 正确设置")
        void builder_allFields() {
            MaterializedViewDef def = sampleView();
            assertThat(def.getName()).isEqualTo("mv_order_summary");
            assertThat(def.getDatabase()).isEqualTo("report");
            assertThat(def.getTargetTable()).isEqualTo("mv_order_summary");
            assertThat(def.getSourceTables()).containsExactly("shop.orders");
            assertThat(def.getDimensions()).containsExactly("order_date", "region");
            assertThat(def.getMetrics()).containsEntry("total_amount", AggregationType.SUM);
            assertThat(def.getMetrics()).containsEntry("order_count", AggregationType.COUNT);
            assertThat(def.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("默认 enabled=true")
        void builder_defaultEnabled() {
            MaterializedViewDef def = MaterializedViewDef.builder()
                    .name("mv").database("d").targetTable("t")
                    .addSourceTable("s.t").addDimension("dim")
                    .build();
            assertThat(def.isEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("validate — 完整性校验")
    class ValidateTest {

        @Test
        @DisplayName("完整定义 — 通过校验")
        void validate_ok() {
            sampleView().validate();
        }

        @Test
        @DisplayName("名称为空 — 抛出异常")
        void validate_emptyName() {
            MaterializedViewDef def = MaterializedViewDef.builder()
                    .name("").database("d").targetTable("t")
                    .addSourceTable("s.t").addDimension("dim").build();
            assertThatThrownBy(def::validate).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("数据库为空 — 抛出异常")
        void validate_emptyDatabase() {
            MaterializedViewDef def = MaterializedViewDef.builder()
                    .name("mv").database("").targetTable("t")
                    .addSourceTable("s.t").addDimension("dim").build();
            assertThatThrownBy(def::validate).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("源表为空 — 抛出异常")
        void validate_emptySourceTables() {
            MaterializedViewDef def = MaterializedViewDef.builder()
                    .name("mv").database("d").targetTable("t")
                    .addDimension("dim").build();
            assertThatThrownBy(def::validate).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("维度和指标都为空 — 抛出异常")
        void validate_emptyDimensionsAndMetrics() {
            MaterializedViewDef def = MaterializedViewDef.builder()
                    .name("mv").database("d").targetTable("t")
                    .addSourceTable("s.t").build();
            assertThatThrownBy(def::validate).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("toSelectSql — SQL 生成")
    class ToSelectSqlTest {

        @Test
        @DisplayName("含维度和指标 — 正确生成 GROUP BY")
        void toSelectSql_withDimensions() {
            MaterializedViewDef def = sampleView();
            String sql = def.toSelectSql();
            assertThat(sql).contains("SELECT order_date, region");
            assertThat(sql).contains("SUM(total_amount) AS total_amount");
            assertThat(sql).contains("COUNT(*) AS order_count");
            assertThat(sql).contains("FROM shop.orders");
            assertThat(sql).contains("GROUP BY order_date, region");
        }

        @Test
        @DisplayName("含 WHERE 条件")
        void toSelectSql_withFilter() {
            MaterializedViewDef def = MaterializedViewDef.builder()
                    .name("mv").database("d").targetTable("t")
                    .addSourceTable("shop.orders")
                    .addDimension("region")
                    .addMetric("total", AggregationType.SUM)
                    .filterCondition("status = 'paid'")
                    .build();
            String sql = def.toSelectSql();
            assertThat(sql).contains("WHERE status = 'paid'");
        }

        @Test
        @DisplayName("无维度 — 不生成 GROUP BY")
        void toSelectSql_noDimensions() {
            MaterializedViewDef def = MaterializedViewDef.builder()
                    .name("mv").database("d").targetTable("t")
                    .addSourceTable("shop.orders")
                    .addMetric("total", AggregationType.SUM)
                    .build();
            String sql = def.toSelectSql();
            assertThat(sql).doesNotContain("GROUP BY");
        }
    }

    @Nested
    @DisplayName("toCreateDdl — DDL 生成")
    class ToCreateDdlTest {

        @Test
        @DisplayName("生成 CREATE MATERIALIZED VIEW")
        void toCreateDdl() {
            MaterializedViewDef def = sampleView();
            String ddl = def.toCreateDdl();
            assertThat(ddl).startsWith("CREATE MATERIALIZED VIEW `report`.`mv_order_summary` AS");
            assertThat(ddl).contains("SELECT");
        }
    }

    @Nested
    @DisplayName("辅助方法")
    class HelperMethodTest {

        @Test
        @DisplayName("isAggregationView — 有维度返回 true")
        void isAggregationView() {
            assertThat(sampleView().isAggregationView()).isTrue();
        }

        @Test
        @DisplayName("cdcListenTables — 启用时返回源表")
        void cdcListenTables_enabled() {
            MaterializedViewDef def = sampleView();
            assertThat(def.cdcListenTables()).containsExactly("shop.orders");
        }

        @Test
        @DisplayName("cdcListenTables — 禁用时返回空列表")
        void cdcListenTables_disabled() {
            MaterializedViewDef def = sampleView();
            def.setEnabled(false);
            assertThat(def.cdcListenTables()).isEmpty();
        }
    }

    @Nested
    @DisplayName("equals / hashCode")
    class ObjectMethodsTest {

        @Test
        @DisplayName("equals — 相同定义")
        void equals_same() {
            assertThat(sampleView()).isEqualTo(sampleView());
        }

        @Test
        @DisplayName("equals — 不同定义")
        void equals_different() {
            MaterializedViewDef d1 = sampleView();
            MaterializedViewDef d2 = sampleView();
            d2.setName("different");
            assertThat(d1).isNotEqualTo(d2);
        }
    }
}