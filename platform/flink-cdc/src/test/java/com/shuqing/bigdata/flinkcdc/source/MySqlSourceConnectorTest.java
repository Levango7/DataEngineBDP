package com.shuqing.bigdata.flinkcdc.source;

import com.shuqing.bigdata.flinkcdc.model.ChangeRecord;
import com.shuqing.bigdata.flinkcdc.model.ChangeRecord.Op;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MySqlSourceConnector} 单元测试。
 *
 * <p>测试覆盖：配置校验、启动模式解析、变更记录转换等纯逻辑；
 * 实际 MySqlSource 构建依赖 Flink CDC 运行时，通过 {@code createSource} 间接验证。</p>
 *
 * @author shuqing-bigdata
 */
class MySqlSourceConnectorTest {

    /**
     * 构造一个合法的默认 SourceConfig。
     */
    private SourceConfig validConfig() {
        return new SourceConfig.Builder()
                .name("test-mysql")
                .type(SourceConfig.SourceType.MYSQL)
                .host("127.0.0.1")
                .port(3306)
                .username("cdc")
                .password("pass")
                .database("shop")
                .table("shop.orders")
                .serverId(5400)
                .startupMode(SourceConfig.StartupMode.INITIAL)
                .build();
    }

    @Nested
    @DisplayName("validate — 配置校验")
    class ValidateTest {

        @Test
        @DisplayName("合法配置 — 校验通过")
        void validate_validConfig_passes() {
            MySqlSourceConnector.validate(validConfig());
        }

        @Test
        @DisplayName("host 为空 — 抛出异常")
        void validate_emptyHost_throws() {
            SourceConfig config = validConfig();
            config.setHost("");
            assertThatThrownBy(() -> MySqlSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("host");
        }

        @Test
        @DisplayName("username 为空 — 抛出异常")
        void validate_emptyUsername_throws() {
            SourceConfig config = validConfig();
            config.setUsername(null);
            assertThatThrownBy(() -> MySqlSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("username");
        }

        @Test
        @DisplayName("database 为空 — 抛出异常")
        void validate_emptyDatabase_throws() {
            SourceConfig config = validConfig();
            config.setDatabase("  ");
            assertThatThrownBy(() -> MySqlSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("database");
        }

        @Test
        @DisplayName("table 为空 — 抛出异常")
        void validate_emptyTable_throws() {
            SourceConfig config = validConfig();
            config.setTable(null);
            assertThatThrownBy(() -> MySqlSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("table");
        }

        @Test
        @DisplayName("serverId <= 0 — 抛出异常")
        void validate_nonPositiveServerId_throws() {
            SourceConfig config = validConfig();
            config.setServerId(0);
            assertThatThrownBy(() -> MySqlSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("serverId");
        }

        @Test
        @DisplayName("TIMESTAMP 模式未指定时间戳 — 抛出异常")
        void validate_timestampWithoutMillis_throws() {
            SourceConfig config = validConfig();
            config.setStartupMode(SourceConfig.StartupMode.TIMESTAMP);
            config.setStartupTimestampMillis(null);
            assertThatThrownBy(() -> MySqlSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("startupTimestampMillis");
        }

        @Test
        @DisplayName("SPECIFIC_OFFSET 模式未指定文件名 — 抛出异常")
        void validate_specificOffsetWithoutFilename_throws() {
            SourceConfig config = validConfig();
            config.setStartupMode(SourceConfig.StartupMode.SPECIFIC_OFFSET);
            config.setBinlogFilename(null);
            config.setBinlogPosition(1234L);
            assertThatThrownBy(() -> MySqlSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("binlogFilename");
        }

        @Test
        @DisplayName("SPECIFIC_OFFSET 模式未指定位点 — 抛出异常")
        void validate_specificOffsetWithoutPosition_throws() {
            SourceConfig config = validConfig();
            config.setStartupMode(SourceConfig.StartupMode.SPECIFIC_OFFSET);
            config.setBinlogFilename("binlog.000001");
            config.setBinlogPosition(null);
            assertThatThrownBy(() -> MySqlSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("binlogPosition");
        }

        @Test
        @DisplayName("SPECIFIC_OFFSET 模式完整配置 — 校验通过")
        void validate_specificOffsetComplete_passes() {
            SourceConfig config = validConfig();
            config.setStartupMode(SourceConfig.StartupMode.SPECIFIC_OFFSET);
            config.setBinlogFilename("binlog.000001");
            config.setBinlogPosition(1234L);
            MySqlSourceConnector.validate(config);
        }
    }

    @Nested
    @DisplayName("toChangeRecord — 变更记录转换")
    class ToChangeRecordTest {

        @Test
        @DisplayName("INSERT 记录 — before 为 null")
        void toChangeRecord_insert() {
            Map<String, Object> after = Map.of("id", 1, "name", "alice");
            ChangeRecord record = MySqlSourceConnector.toChangeRecord(null, after, "c", null, 100L);

            assertThat(record.getBefore()).isNull();
            assertThat(record.getAfter()).isEqualTo(after);
            assertThat(record.getOp()).isEqualTo("c");
            assertThat(record.isInsert()).isTrue();
        }

        @Test
        @DisplayName("UPDATE 记录 — before 和 after 均存在")
        void toChangeRecord_update() {
            Map<String, Object> before = Map.of("id", 1, "name", "old");
            Map<String, Object> after = Map.of("id", 1, "name", "new");
            ChangeRecord record = MySqlSourceConnector.toChangeRecord(before, after, "u", null, 200L);

            assertThat(record.getBefore()).isEqualTo(before);
            assertThat(record.getAfter()).isEqualTo(after);
            assertThat(record.isUpdate()).isTrue();
        }

        @Test
        @DisplayName("DELETE 记录 — after 为 null")
        void toChangeRecord_delete() {
            Map<String, Object> before = Map.of("id", 1, "name", "alice");
            ChangeRecord record = MySqlSourceConnector.toChangeRecord(before, null, "d", null, 300L);

            assertThat(record.getBefore()).isEqualTo(before);
            assertThat(record.getAfter()).isNull();
            assertThat(record.isDelete()).isTrue();
        }

        @Test
        @DisplayName("SNAPSHOT 记录 — op=r")
        void toChangeRecord_snapshot() {
            ChangeRecord record = MySqlSourceConnector.toChangeRecord(
                    null, Map.of("id", 1), "r", Map.of("db", "shop"), 400L);
            assertThat(record.isSnapshot()).isTrue();
            assertThat(record.opEnum()).isEqualTo(Op.SNAPSHOT);
        }

        @Test
        @DisplayName("包含 source 元数据")
        void toChangeRecord_withSource() {
            Map<String, Object> source = Map.of(
                    "db", "shop", "table", "orders",
                    "file", "binlog.000001", "pos", 1234, "gtid", "abc-1:5");
            ChangeRecord record = MySqlSourceConnector.toChangeRecord(null, Map.of("id", 1), "c", source, 500L);

            assertThat(record.getSource()).isEqualTo(source);
            assertThat(record.getSource()).containsKey("gtid");
        }
    }

    @Nested
    @DisplayName("常量字段名")
    class ConstantsTest {

        @Test
        @DisplayName("Debezium 字段名常量正确")
        void fieldConstants() {
            assertThat(MySqlSourceConnector.FIELD_BEFORE).isEqualTo("before");
            assertThat(MySqlSourceConnector.FIELD_AFTER).isEqualTo("after");
            assertThat(MySqlSourceConnector.FIELD_OP).isEqualTo("op");
            assertThat(MySqlSourceConnector.FIELD_SOURCE).isEqualTo("source");
            assertThat(MySqlSourceConnector.FIELD_TS_MS).isEqualTo("ts_ms");
        }
    }

    @Nested
    @DisplayName("createSource — null 检查")
    class CreateSourceTest {

        @Test
        @DisplayName("config 为 null — 抛出 NPE")
        void createSource_nullConfig_throwsNpe() {
            assertThatThrownBy(() -> MySqlSourceConnector.createSource(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}