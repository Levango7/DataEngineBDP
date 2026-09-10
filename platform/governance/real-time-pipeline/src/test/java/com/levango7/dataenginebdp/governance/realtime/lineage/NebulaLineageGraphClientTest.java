package com.levango7.dataenginebdp.governance.realtime.lineage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * {@link NebulaLineageGraphClient} nGQL 注入防护单元测试。
 *
 * <p>验证 {@code validateNebulaIdentifier} 白名单校验逻辑，确保字段名、表名、边类型、
 * 节点标签等标识符在拼入 nGQL 语句前经过严格校验，阻断 nGQL 注入攻击向量
 * （如 {@code ; DROP SPACE}、{@code ' OR 1=1}、{@code --comment}、保留关键字）。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>合法标识符（字母、数字、下划线、连字符）通过校验</li>
 *   <li>含特殊字符的标识符被拒绝</li>
 *   <li>nGQL 保留关键字（含大小写变体）被拒绝</li>
 *   <li>空字符串与超长字符串被拒绝</li>
 *   <li>构造函数对配置值（space/nodeTag/edgeType）的校验</li>
 * </ul>
 */
@DisplayName("NebulaLineageGraphClient nGQL 注入防护")
class NebulaLineageGraphClientTest {

    /** 合法配置值，用于构造可调用 validateNebulaIdentifier 的客户端实例 */
    private NebulaLineageGraphClient client;

    @BeforeEach
    void setUp() {
        client = new NebulaLineageGraphClient(
                "localhost", 9669, "lineage", "TableField", "FieldLineage");
    }

    @Nested
    @DisplayName("合法标识符应通过校验")
    class ValidIdentifier {

        @Test
        @DisplayName("纯字母标识符通过校验")
        void pureLetters() {
            assertThatNoException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("lineage", "space"));
        }

        @Test
        @DisplayName("含下划线的标识符通过校验")
        void withUnderscore() {
            assertThatNoException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("source_table", "sourceTable"));
        }

        @Test
        @DisplayName("含连字符的标识符通过校验")
        void withHyphen() {
            assertThatNoException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("my-tag", "nodeTag"));
        }

        @Test
        @DisplayName("字母数字混合的标识符通过校验")
        void alphanumeric() {
            assertThatNoException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("field123", "fieldName"));
        }

        @Test
        @DisplayName("单字符标识符通过校验（长度下界）")
        void singleChar() {
            assertThatNoException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("a", "fieldName"));
        }

        @Test
        @DisplayName("128 字符标识符通过校验（长度上界）")
        void maxLengthBoundary() {
            String name = "a".repeat(128);
            assertThatNoException()
                    .isThrownBy(() -> client.validateNebulaIdentifier(name, "fieldName"));
        }
    }

    @Nested
    @DisplayName("含特殊字符的标识符应被拒绝")
    class InvalidCharacters {

        @Test
        @DisplayName("分号 + DROP SPACE 注入被拒绝")
        void semicolonDropSpace() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("; DROP SPACE", "fieldName"))
                    .withMessageContaining("非法字符");
        }

        @Test
        @DisplayName("单引号 OR 1=1 注入被拒绝")
        void quoteOrInjection() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("' OR 1=1", "fieldName"))
                    .withMessageContaining("非法字符");
        }

        @Test
        @DisplayName("SQL 注释 --comment 被拒绝（以连字符开头）")
        void sqlComment() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("--comment", "fieldName"))
                    .withMessageContaining("连字符开头");
        }

        @Test
        @DisplayName("字段名内嵌分号拼接 DROP 被拒绝")
        void embeddedSemicolonDrop() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("field;DROP", "fieldName"))
                    .withMessageContaining("非法字符");
        }

        @Test
        @DisplayName("含空格的标识符被拒绝")
        void containsSpace() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("a b", "fieldName"))
                    .withMessageContaining("非法字符");
        }

        @Test
        @DisplayName("含点号的标识符被拒绝（防止 table.field 逃逸）")
        void containsDot() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("a.b", "fieldName"))
                    .withMessageContaining("非法字符");
        }

        @Test
        @DisplayName("含双引号的标识符被拒绝（防止闭合 nGQL 字符串）")
        void containsDoubleQuote() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("a\"b", "fieldName"))
                    .withMessageContaining("非法字符");
        }

        @Test
        @DisplayName("含反斜杠的标识符被拒绝（防止转义逃逸）")
        void containsBackslash() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("a\\b", "fieldName"))
                    .withMessageContaining("非法字符");
        }

        @Test
        @DisplayName("含右括号的标识符被拒绝（防止逃逸 VALUES 列表）")
        void containsClosingParen() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("field)", "fieldName"))
                    .withMessageContaining("非法字符");
        }

        @Test
        @DisplayName("含块注释 /* 的标识符被拒绝")
        void containsBlockComment() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("field/*", "fieldName"))
                    .withMessageContaining("非法字符");
        }
    }

    @Nested
    @DisplayName("nGQL 保留关键字应被拒绝")
    class NgqlKeywords {

        @Test
        @DisplayName("大写关键字 DROP 被拒绝")
        void dropUpperCase() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("DROP", "fieldName"))
                    .withMessageContaining("保留关键字");
        }

        @Test
        @DisplayName("小写关键字 drop 被拒绝（大小写不敏感）")
        void dropLowerCase() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("drop", "fieldName"))
                    .withMessageContaining("保留关键字");
        }

        @Test
        @DisplayName("混合大小写关键字 Drop 被拒绝（大小写不敏感）")
        void dropMixedCase() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("Drop", "fieldName"))
                    .withMessageContaining("保留关键字");
        }

        @Test
        @DisplayName("关键字 INSERT 被拒绝")
        void insert() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("INSERT", "fieldName"))
                    .withMessageContaining("保留关键字");
        }

        @Test
        @DisplayName("关键字 VERTEX 被拒绝")
        void vertex() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("VERTEX", "fieldName"))
                    .withMessageContaining("保留关键字");
        }

        @Test
        @DisplayName("关键字 SPACE 被拒绝")
        void space() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("SPACE", "fieldName"))
                    .withMessageContaining("保留关键字");
        }

        @Test
        @DisplayName("关键字 DELETE 被拒绝")
        void delete() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("DELETE", "fieldName"))
                    .withMessageContaining("保留关键字");
        }
    }

    @Nested
    @DisplayName("空字符串与超长字符串应被拒绝")
    class BoundaryCases {

        @Test
        @DisplayName("null 标识符被拒绝")
        void nullIdentifier() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> client.validateNebulaIdentifier(null, "fieldName"))
                    .withMessageContaining("不能为空");
        }

        @Test
        @DisplayName("空字符串标识符被拒绝")
        void emptyIdentifier() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> client.validateNebulaIdentifier("", "fieldName"))
                    .withMessageContaining("不能为空");
        }

        @Test
        @DisplayName("129 字符标识符被拒绝（超过长度上界）")
        void overMaxLength() {
            String name = "a".repeat(129);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> client.validateNebulaIdentifier(name, "fieldName"))
                    .withMessageContaining("长度超过");
        }
    }

    @Nested
    @DisplayName("构造函数配置值校验")
    class ConstructorValidation {

        @Test
        @DisplayName("合法配置应成功构造客户端")
        void validConfigConstructsSuccessfully() {
            NebulaLineageGraphClient c = new NebulaLineageGraphClient(
                    "localhost", 9669, "lineage", "TableField", "FieldLineage");

            assertThat(c.getSpace()).isEqualTo("lineage");
            assertThat(c.getNodeTag()).isEqualTo("TableField");
            assertThat(c.getEdgeType()).isEqualTo("FieldLineage");
        }

        @Test
        @DisplayName("非法 space 配置应抛出 IllegalArgumentException")
        void invalidSpace() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new NebulaLineageGraphClient(
                            "localhost", 9669, "lineage;DROP", "TableField", "FieldLineage"))
                    .withMessageContaining("governance.nebula.space");
        }

        @Test
        @DisplayName("非法 nodeTag 配置应抛出 IllegalArgumentException")
        void invalidNodeTag() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new NebulaLineageGraphClient(
                            "localhost", 9669, "lineage", "tag'OR'1", "FieldLineage"))
                    .withMessageContaining("governance.nebula.node-tag");
        }

        @Test
        @DisplayName("非法 edgeType 配置应抛出 IllegalArgumentException")
        void invalidEdgeType() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new NebulaLineageGraphClient(
                            "localhost", 9669, "lineage", "TableField", "DROP"))
                    .withMessageContaining("governance.nebula.edge-type");
        }

        @Test
        @DisplayName("关键字作为 space 配置应被拒绝")
        void keywordAsSpace() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new NebulaLineageGraphClient(
                            "localhost", 9669, "SPACE", "TableField", "FieldLineage"))
                    .withMessageContaining("保留关键字");
        }
    }
}