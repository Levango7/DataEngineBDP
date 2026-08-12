package com.levango7.dataenginebdp.flinkcdc.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SourceConfig} 单元测试。
 *
 * @author shuqing-bigdata
 */
class SourceConfigTest {

    @Nested
    @DisplayName("StartupMode 枚举")
    class StartupModeTest {

        @Test
        @DisplayName("fromCode — 正确解析所有模式")
        void fromCode_allModes() {
            assertThat(SourceConfig.StartupMode.fromCode("initial")).isEqualTo(SourceConfig.StartupMode.INITIAL);
            assertThat(SourceConfig.StartupMode.fromCode("latest-offset")).isEqualTo(SourceConfig.StartupMode.LATEST_OFFSET);
            assertThat(SourceConfig.StartupMode.fromCode("timestamp")).isEqualTo(SourceConfig.StartupMode.TIMESTAMP);
            assertThat(SourceConfig.StartupMode.fromCode("specific-offset")).isEqualTo(SourceConfig.StartupMode.SPECIFIC_OFFSET);
        }

        @Test
        @DisplayName("fromCode — 大小写不敏感")
        void fromCode_caseInsensitive() {
            assertThat(SourceConfig.StartupMode.fromCode("INITIAL")).isEqualTo(SourceConfig.StartupMode.INITIAL);
            assertThat(SourceConfig.StartupMode.fromCode("LATEST-OFFSET")).isEqualTo(SourceConfig.StartupMode.LATEST_OFFSET);
        }

        @Test
        @DisplayName("fromCode — 下划线转连字符")
        void fromCode_underscoreToHyphen() {
            assertThat(SourceConfig.StartupMode.fromCode("latest_offset")).isEqualTo(SourceConfig.StartupMode.LATEST_OFFSET);
            assertThat(SourceConfig.StartupMode.fromCode("specific_offset")).isEqualTo(SourceConfig.StartupMode.SPECIFIC_OFFSET);
        }

        @Test
        @DisplayName("fromCode — null 抛出 NPE")
        void fromCode_null_throwsNpe() {
            assertThatThrownBy(() -> SourceConfig.StartupMode.fromCode(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("fromCode — 未知模式抛出异常")
        void fromCode_unknown_throws() {
            assertThatThrownBy(() -> SourceConfig.StartupMode.fromCode("never"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("code — 返回正确编码")
        void code_returnsCorrectCodes() {
            assertThat(SourceConfig.StartupMode.INITIAL.code()).isEqualTo("initial");
            assertThat(SourceConfig.StartupMode.LATEST_OFFSET.code()).isEqualTo("latest-offset");
        }
    }

    @Nested
    @DisplayName("jdbcUrl — 连接 URL 生成")
    class JdbcUrlTest {

        @Test
        @DisplayName("MySQL — 正确 URL")
        void mysqlUrl() {
            SourceConfig config = new SourceConfig.Builder()
                    .type(SourceConfig.SourceType.MYSQL)
                    .host("127.0.0.1").port(3306).database("shop").build();
            assertThat(config.jdbcUrl()).isEqualTo("jdbc:mysql://127.0.0.1:3306/shop");
        }

        @Test
        @DisplayName("PostgreSQL — 正确 URL")
        void postgresqlUrl() {
            SourceConfig config = new SourceConfig.Builder()
                    .type(SourceConfig.SourceType.POSTGRESQL)
                    .host("pg-host").port(5432).database("app").build();
            assertThat(config.jdbcUrl()).isEqualTo("jdbc:postgresql://pg-host:5432/app");
        }

        @Test
        @DisplayName("Oracle — 正确 URL")
        void oracleUrl() {
            SourceConfig config = new SourceConfig.Builder()
                    .type(SourceConfig.SourceType.ORACLE)
                    .host("ora-host").port(1521).database("ORCL").build();
            assertThat(config.jdbcUrl()).isEqualTo("jdbc:oracle:thin:@ora-host:1521:ORCL");
        }

        @Test
        @DisplayName("database 为 null — URL 不含库名")
        void nullDatabase() {
            SourceConfig config = new SourceConfig.Builder()
                    .type(SourceConfig.SourceType.MYSQL)
                    .host("h").port(3306).build();
            config.setDatabase(null);
            assertThat(config.jdbcUrl()).isEqualTo("jdbc:mysql://h:3306/");
        }
    }

    @Nested
    @DisplayName("parseTable — 表名解析")
    class ParseTableTest {

        @Test
        @DisplayName("db.table 格式 — 正确拆分")
        void parseTable_dbTableFormat() {
            SourceConfig config = new SourceConfig.Builder().database("d").table("shop.orders").build();
            String[] parts = config.parseTable();
            assertThat(parts).containsExactly("shop", "orders");
        }

        @Test
        @DisplayName("仅 table 名 — 使用 database")
        void parseTable_tableOnly() {
            SourceConfig config = new SourceConfig.Builder().database("shop").table("orders").build();
            String[] parts = config.parseTable();
            assertThat(parts).containsExactly("shop", "orders");
        }

        @Test
        @DisplayName("table 为 null — 返回 [null, null]")
        void parseTable_null() {
            SourceConfig config = new SourceConfig.Builder().build();
            config.setTable(null);
            String[] parts = config.parseTable();
            assertThat(parts).containsExactly(null, null);
        }

        @Test
        @DisplayName("table 为空字符串 — 返回 [null, null]")
        void parseTable_empty() {
            SourceConfig config = new SourceConfig.Builder().build();
            config.setTable("  ");
            String[] parts = config.parseTable();
            assertThat(parts).containsExactly(null, null);
        }
    }

    @Nested
    @DisplayName("resolvedTables — 表列表解析")
    class ResolvedTablesTest {

        @Test
        @DisplayName("仅 table — 返回单元素列表")
        void resolvedTables_tableOnly() {
            SourceConfig config = new SourceConfig.Builder().table("shop.orders").build();
            assertThat(config.resolvedTables()).containsExactly("shop.orders");
        }

        @Test
        @DisplayName("table + tableFilters — 合并去重")
        void resolvedTables_merged() {
            SourceConfig config = new SourceConfig.Builder()
                    .table("shop.orders")
                    .tableFilters("shop.orders", "shop.products", "shop.users")
                    .build();
            List<String> tables = config.resolvedTables();
            assertThat(tables).containsExactlyInAnyOrder("shop.orders", "shop.products", "shop.users");
        }

        @Test
        @DisplayName("仅 tableFilters — 返回 filters")
        void resolvedTables_filtersOnly() {
            SourceConfig config = new SourceConfig.Builder()
                    .tableFilters("a.b", "c.d")
                    .build();
            config.setTable(null);
            assertThat(config.resolvedTables()).containsExactlyInAnyOrder("a.b", "c.d");
        }

        @Test
        @DisplayName("都为空 — 返回空列表")
        void resolvedTables_empty() {
            SourceConfig config = new SourceConfig.Builder().build();
            assertThat(config.resolvedTables()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Builder — 链式构造")
    class BuilderTest {

        @Test
        @DisplayName("全字段构造 — 正确设置")
        void builder_allFields() {
            SourceConfig config = new SourceConfig.Builder()
                    .name("src").type(SourceConfig.SourceType.MYSQL)
                    .host("h").port(3307).username("u").password("p")
                    .database("d").table("d.t").serverId(999)
                    .startupMode(SourceConfig.StartupMode.LATEST_OFFSET)
                    .startupTimestampMillis(123L).parallelism(4)
                    .tableFilters("d.t1", "d.t2").includeSchemaChanges(true)
                    .build();

            assertThat(config.getName()).isEqualTo("src");
            assertThat(config.getHost()).isEqualTo("h");
            assertThat(config.getPort()).isEqualTo(3307);
            assertThat(config.getServerId()).isEqualTo(999L);
            assertThat(config.getStartupMode()).isEqualTo(SourceConfig.StartupMode.LATEST_OFFSET);
            assertThat(config.getStartupTimestampMillis()).isEqualTo(123L);
            assertThat(config.getParallelism()).isEqualTo(4);
            assertThat(config.getTableFilters()).containsExactlyInAnyOrder("d.t1", "d.t2");
            assertThat(config.isIncludeSchemaChanges()).isTrue();
        }

        @Test
        @DisplayName("默认值 — type=MYSQL, port=3306, serverId=5400")
        void builder_defaults() {
            SourceConfig config = new SourceConfig.Builder().build();
            assertThat(config.getType()).isEqualTo(SourceConfig.SourceType.MYSQL);
            assertThat(config.getPort()).isEqualTo(3306);
            assertThat(config.getServerId()).isEqualTo(5400L);
            assertThat(config.getStartupMode()).isEqualTo(SourceConfig.StartupMode.INITIAL);
            assertThat(config.getParallelism()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("equals / hashCode / toString")
    class ObjectMethodsTest {

        @Test
        @DisplayName("equals — 相同字段")
        void equals_same() {
            SourceConfig c1 = new SourceConfig.Builder().name("a").host("h").database("d").table("d.t").build();
            SourceConfig c2 = new SourceConfig.Builder().name("a").host("h").database("d").table("d.t").build();
            assertThat(c1).isEqualTo(c2);
            assertThat(c1.hashCode()).isEqualTo(c2.hashCode());
        }

        @Test
        @DisplayName("equals — 不同字段")
        void equals_different() {
            SourceConfig c1 = new SourceConfig.Builder().name("a").build();
            SourceConfig c2 = new SourceConfig.Builder().name("b").build();
            assertThat(c1).isNotEqualTo(c2);
        }

        @Test
        @DisplayName("toString — 包含 name 和 type")
        void toString_containsNameAndType() {
            SourceConfig config = new SourceConfig.Builder().name("my-src").build();
            assertThat(config.toString()).contains("my-src").contains("MYSQL");
        }
    }

    @Test
    @DisplayName("setTableFilters(null) — 设置为空 Set")
    void setTableFiltersNull() {
        SourceConfig config = new SourceConfig();
        config.setTableFilters(null);
        assertThat(config.getTableFilters()).isEmpty();
    }
}