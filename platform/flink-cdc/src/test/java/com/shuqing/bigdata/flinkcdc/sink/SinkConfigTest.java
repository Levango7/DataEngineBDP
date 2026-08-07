package com.shuqing.bigdata.flinkcdc.sink;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SinkConfig} 单元测试。
 *
 * @author shuqing-bigdata
 */
class SinkConfigTest {

    @Nested
    @DisplayName("WriteMode 枚举")
    class WriteModeTest {

        @Test
        @DisplayName("fromCode — 正确解析所有模式")
        void fromCode_allModes() {
            assertThat(SinkConfig.WriteMode.fromCode("append-only")).isEqualTo(SinkConfig.WriteMode.APPEND_ONLY);
            assertThat(SinkConfig.WriteMode.fromCode("upsert")).isEqualTo(SinkConfig.WriteMode.UPSERT);
            assertThat(SinkConfig.WriteMode.fromCode("overwrite")).isEqualTo(SinkConfig.WriteMode.OVERWRITE);
        }

        @Test
        @DisplayName("fromCode — 枚举名也支持")
        void fromCode_enumName() {
            assertThat(SinkConfig.WriteMode.fromCode("APPEND_ONLY")).isEqualTo(SinkConfig.WriteMode.APPEND_ONLY);
            assertThat(SinkConfig.WriteMode.fromCode("UPSERT")).isEqualTo(SinkConfig.WriteMode.UPSERT);
        }

        @Test
        @DisplayName("fromCode — 大小写不敏感")
        void fromCode_caseInsensitive() {
            assertThat(SinkConfig.WriteMode.fromCode("UPSERT")).isEqualTo(SinkConfig.WriteMode.UPSERT);
            assertThat(SinkConfig.WriteMode.fromCode("Overwrite")).isEqualTo(SinkConfig.WriteMode.OVERWRITE);
        }

        @Test
        @DisplayName("fromCode — null 抛出 NPE")
        void fromCode_null_throwsNpe() {
            assertThatThrownBy(() -> SinkConfig.WriteMode.fromCode(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("fromCode — 未知模式抛出异常")
        void fromCode_unknown_throws() {
            assertThatThrownBy(() -> SinkConfig.WriteMode.fromCode("invalid"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("code — 返回正确编码")
        void code_returnsCorrectCodes() {
            assertThat(SinkConfig.WriteMode.APPEND_ONLY.code()).isEqualTo("append-only");
            assertThat(SinkConfig.WriteMode.UPSERT.code()).isEqualTo("upsert");
            assertThat(SinkConfig.WriteMode.OVERWRITE.code()).isEqualTo("overwrite");
        }
    }

    @Nested
    @DisplayName("property — 额外属性存取")
    class PropertyTest {

        @Test
        @DisplayName("property(key, value) — 设置属性")
        void property_setAndGet() {
            SinkConfig config = new SinkConfig();
            config.property("acks", "all");
            assertThat(config.property("acks")).isEqualTo("all");
        }

        @Test
        @DisplayName("property — 不存在的键返回 null")
        void property_nonExistent_returnsNull() {
            SinkConfig config = new SinkConfig();
            assertThat(config.property("nonexistent")).isNull();
        }

        @Test
        @DisplayName("setProperties(null) — 初始化为空 Map")
        void setPropertiesNull() {
            SinkConfig config = new SinkConfig();
            config.setProperties(null);
            assertThat(config.getProperties()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("setProperties — 替换整个 Map")
        void setProperties_replace() {
            SinkConfig config = new SinkConfig();
            Map<String, String> props = new HashMap<>();
            props.put("k1", "v1");
            props.put("k2", "v2");
            config.setProperties(props);
            assertThat(config.getProperties()).hasSize(2).containsEntry("k1", "v1");
        }
    }

    @Nested
    @DisplayName("默认值")
    class DefaultsTest {

        @Test
        @DisplayName("新实例 — 默认值正确")
        void newInstance_defaults() {
            SinkConfig config = new SinkConfig();
            assertThat(config.getType()).isEqualTo(SinkConfig.SinkType.KAFKA);
            assertThat(config.getHost()).isEqualTo("localhost");
            assertThat(config.getPort()).isEqualTo(9092);
            assertThat(config.getWriteMode()).isEqualTo(SinkConfig.WriteMode.UPSERT);
            assertThat(config.getFormat()).isEqualTo("debezium-json");
            assertThat(config.getParallelism()).isEqualTo(1);
            assertThat(config.getProperties()).isNotNull();
        }
    }

    @Nested
    @DisplayName("getter/setter")
    class AccessorTest {

        @Test
        @DisplayName("所有字段可存取")
        void allFieldsAccessible() {
            SinkConfig config = new SinkConfig();
            config.setName("my-sink");
            config.setType(SinkConfig.SinkType.DORIS);
            config.setHost("doris-host");
            config.setPort(8030);
            config.setTopic("doris-topic");
            config.setUsername("root");
            config.setPassword("secret");
            config.setWriteMode(SinkConfig.WriteMode.APPEND_ONLY);
            config.setPrimaryKey("id,ts");
            config.setFormat("canal-json");
            config.setParallelism(3);

            assertThat(config.getName()).isEqualTo("my-sink");
            assertThat(config.getType()).isEqualTo(SinkConfig.SinkType.DORIS);
            assertThat(config.getHost()).isEqualTo("doris-host");
            assertThat(config.getPort()).isEqualTo(8030);
            assertThat(config.getTopic()).isEqualTo("doris-topic");
            assertThat(config.getUsername()).isEqualTo("root");
            assertThat(config.getPassword()).isEqualTo("secret");
            assertThat(config.getWriteMode()).isEqualTo(SinkConfig.WriteMode.APPEND_ONLY);
            assertThat(config.getPrimaryKey()).isEqualTo("id,ts");
            assertThat(config.getFormat()).isEqualTo("canal-json");
            assertThat(config.getParallelism()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("equals / hashCode / toString")
    class ObjectMethodsTest {

        @Test
        @DisplayName("equals — 相同字段")
        void equals_same() {
            SinkConfig c1 = new SinkConfig();
            c1.setName("a");
            c1.setTopic("t");
            SinkConfig c2 = new SinkConfig();
            c2.setName("a");
            c2.setTopic("t");
            assertThat(c1).isEqualTo(c2);
            assertThat(c1.hashCode()).isEqualTo(c2.hashCode());
        }

        @Test
        @DisplayName("equals — 不同字段")
        void equals_different() {
            SinkConfig c1 = new SinkConfig();
            c1.setName("a");
            SinkConfig c2 = new SinkConfig();
            c2.setName("b");
            assertThat(c1).isNotEqualTo(c2);
        }

        @Test
        @DisplayName("equals — 与 null 和其他类型比较")
        void equals_nullAndOtherType() {
            SinkConfig config = new SinkConfig();
            assertThat(config).isNotEqualTo(null);
            assertThat(config).isNotEqualTo("string");
            assertThat(config).isEqualTo(config);
        }

        @Test
        @DisplayName("toString — 包含 name 和 type")
        void toString_containsNameAndType() {
            SinkConfig config = new SinkConfig();
            config.setName("my-kafka");
            assertThat(config.toString()).contains("my-kafka").contains("KAFKA");
        }
    }
}