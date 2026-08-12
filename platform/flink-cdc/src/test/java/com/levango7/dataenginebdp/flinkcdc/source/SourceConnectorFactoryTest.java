package com.levango7.dataenginebdp.flinkcdc.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SourceConnectorFactory} 单元测试。
 *
 * <p>测试覆盖：根据 sourceType 返回正确连接器、未知类型异常处理、
 * 配置转换（toPostgresConfig / toOracleConfig）、parseSourceType 等。</p>
 *
 * @author shuqing-bigdata
 */
class SourceConnectorFactoryTest {

    @Nested
    @DisplayName("parseSourceType — 类型字符串解析")
    class ParseSourceTypeTest {

        @Test
        @DisplayName("mysql → MYSQL")
        void parse_mysql() {
            assertThat(SourceConnectorFactory.parseSourceType("mysql"))
                    .isEqualTo(SourceConfig.SourceType.MYSQL);
        }

        @Test
        @DisplayName("MYSQL 大写 → MYSQL")
        void parse_mysqlUpper() {
            assertThat(SourceConnectorFactory.parseSourceType("MYSQL"))
                    .isEqualTo(SourceConfig.SourceType.MYSQL);
        }

        @Test
        @DisplayName("postgresql → POSTGRESQL")
        void parse_postgresql() {
            assertThat(SourceConnectorFactory.parseSourceType("postgresql"))
                    .isEqualTo(SourceConfig.SourceType.POSTGRESQL);
        }

        @Test
        @DisplayName("postgres → POSTGRESQL")
        void parse_postgres() {
            assertThat(SourceConnectorFactory.parseSourceType("postgres"))
                    .isEqualTo(SourceConfig.SourceType.POSTGRESQL);
        }

        @Test
        @DisplayName("pg → POSTGRESQL")
        void parse_pg() {
            assertThat(SourceConnectorFactory.parseSourceType("pg"))
                    .isEqualTo(SourceConfig.SourceType.POSTGRESQL);
        }

        @Test
        @DisplayName("oracle → ORACLE")
        void parse_oracle() {
            assertThat(SourceConnectorFactory.parseSourceType("oracle"))
                    .isEqualTo(SourceConfig.SourceType.ORACLE);
        }

        @Test
        @DisplayName("ora → ORACLE")
        void parse_ora() {
            assertThat(SourceConnectorFactory.parseSourceType("ora"))
                    .isEqualTo(SourceConfig.SourceType.ORACLE);
        }

        @Test
        @DisplayName("带空格的类型 — 正确解析")
        void parse_withSpaces() {
            assertThat(SourceConnectorFactory.parseSourceType("  postgresql  "))
                    .isEqualTo(SourceConfig.SourceType.POSTGRESQL);
        }

        @Test
        @DisplayName("未知类型 — 抛出异常")
        void parse_unknown_throws() {
            assertThatThrownBy(() -> SourceConnectorFactory.parseSourceType("redis"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("redis");
        }

        @Test
        @DisplayName("null — 抛出 NPE")
        void parse_null_throwsNpe() {
            assertThatThrownBy(() -> SourceConnectorFactory.parseSourceType(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("toPostgresConfig — 基础配置转换为 PG 配置")
    class ToPostgresConfigTest {

        @Test
        @DisplayName("基础配置 → 默认 PG 配置（slot/plugin/schema）")
        void toPostgresConfig_defaults() {
            SourceConfig base = new SourceConfig.Builder()
                    .name("test").type(SourceConfig.SourceType.POSTGRESQL)
                    .host("127.0.0.1").port(5432)
                    .username("cdc").password("pass")
                    .database("shop").table("public.orders")
                    .build();

            PostgresSourceConfig pgConfig = SourceConnectorFactory.toPostgresConfig(base);

            assertThat(pgConfig.getSlotName()).isEqualTo("flink_slot");
            assertThat(pgConfig.getDecodingPlugin()).isEqualTo(PostgresSourceConfig.DecodingPlugin.PGOUTPUT);
            assertThat(pgConfig.getSchemaList()).contains("public");
            assertThat(pgConfig.getTableList()).contains("public.orders");
            assertThat(pgConfig.getBase()).isEqualTo(base);
        }

        @Test
        @DisplayName("base 为 null — 抛出 NPE")
        void toPostgresConfig_nullBase_throwsNpe() {
            assertThatThrownBy(() -> SourceConnectorFactory.toPostgresConfig(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("table 为 null — tableList 为空")
        void toPostgresConfig_nullTable() {
            SourceConfig base = new SourceConfig.Builder()
                    .name("test").type(SourceConfig.SourceType.POSTGRESQL)
                    .host("127.0.0.1").port(5432)
                    .username("cdc").password("pass")
                    .database("shop")
                    .build();

            PostgresSourceConfig pgConfig = SourceConnectorFactory.toPostgresConfig(base);
            assertThat(pgConfig.getTableList()).isEmpty();
        }
    }

    @Nested
    @DisplayName("toOracleConfig — 基础配置转换为 Oracle 配置")
    class ToOracleConfigTest {

        @Test
        @DisplayName("基础配置 → 默认 Oracle 配置（logMiner/useXStream/schema/table 大写）")
        void toOracleConfig_defaults() {
            SourceConfig base = new SourceConfig.Builder()
                    .name("test").type(SourceConfig.SourceType.ORACLE)
                    .host("127.0.0.1").port(1521)
                    .username("cdc").password("pass")
                    .database("ORCLPDB1").table("shop.orders")
                    .build();

            OracleSourceConfig oraConfig = SourceConnectorFactory.toOracleConfig(base);

            assertThat(oraConfig.getLogMinerOption()).isEqualTo(OracleSourceConfig.LogMinerOption.BOTH);
            assertThat(oraConfig.isUseXStream()).isFalse();
            assertThat(oraConfig.getServiceName()).isEqualTo("ORCLPDB1");
            assertThat(oraConfig.getSchemaList()).contains("SHOP");
            assertThat(oraConfig.getTableList()).contains("SHOP.ORDERS");
            assertThat(oraConfig.getBase()).isEqualTo(base);
        }

        @Test
        @DisplayName("base 为 null — 抛出 NPE")
        void toOracleConfig_nullBase_throwsNpe() {
            assertThatThrownBy(() -> SourceConnectorFactory.toOracleConfig(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("database 为 null — serviceName 为 null")
        void toOracleConfig_nullDatabase() {
            SourceConfig base = new SourceConfig.Builder()
                    .name("test").type(SourceConfig.SourceType.ORACLE)
                    .host("127.0.0.1").port(1521)
                    .username("cdc").password("pass")
                    .table("shop.orders")
                    .build();

            OracleSourceConfig oraConfig = SourceConnectorFactory.toOracleConfig(base);
            assertThat(oraConfig.getServiceName()).isNull();
        }
    }

    @Nested
    @DisplayName("createSource — null 检查与类型校验")
    class CreateSourceTest {

        @Test
        @DisplayName("config 为 null — 抛出 NPE")
        void createSource_nullConfig_throwsNpe() {
            assertThatThrownBy(() -> SourceConnectorFactory.createSource(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("type 为 null — 抛出 IllegalArgumentException")
        void createSource_nullType_throws() {
            SourceConfig config = new SourceConfig();
            config.setType(null);
            assertThatThrownBy(() -> SourceConnectorFactory.createSource(config))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("type");
        }
    }
}