package com.levango7.dataenginebdp.sqlgateway.virtual.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PredicateParser} 单元测试。
 *
 * <p>验证 SQL 注入防护：谓词字符串被正确解析为参数化 SQL 片段，
 * 值通过参数绑定传递，恶意输入被拒绝。</p>
 */
class PredicateParserTest {

    @Nested
    @DisplayName("空输入处理")
    class EmptyInput {

        @Test
        @DisplayName("null 谓词返回空结果")
        void nullPredicate_shouldReturnEmpty() {
            PredicateParser.ParsedPredicate result = PredicateParser.parse(null);

            assertThat(result.sqlFragment()).isEmpty();
            assertThat(result.parameters()).isEmpty();
            assertThat(result.hasPredicate()).isFalse();
        }

        @Test
        @DisplayName("空白谓词返回空结果")
        void blankPredicate_shouldReturnEmpty() {
            PredicateParser.ParsedPredicate result = PredicateParser.parse("   ");

            assertThat(result.hasPredicate()).isFalse();
        }
    }

    @Nested
    @DisplayName("等值谓词 col = value")
    class EqualityPredicate {

        @Test
        @DisplayName("字符串值：name = 'alice' → name = ? + [alice]")
        void stringValue() {
            PredicateParser.ParsedPredicate result = PredicateParser.parse("name = 'alice'");

            assertThat(result.sqlFragment()).isEqualTo("name = ?");
            assertThat(result.parameters()).containsExactly("alice");
        }

        @Test
        @DisplayName("整数值：id = 100 → id = ? + [100L]")
        void intValue() {
            PredicateParser.ParsedPredicate result = PredicateParser.parse("id = 100");

            assertThat(result.sqlFragment()).isEqualTo("id = ?");
            assertThat(result.parameters()).containsExactly(100L);
        }

        @Test
        @DisplayName("双引号字符串：name = \"bob\" → name = ? + [bob]")
        void doubleQuotedValue() {
            PredicateParser.ParsedPredicate result = PredicateParser.parse("name = \"bob\"");

            assertThat(result.parameters()).containsExactly("bob");
        }
    }

    @Nested
    @DisplayName("范围谓词 col OP value")
    class RangePredicate {

        @Test
        @DisplayName("大于：age > 18 → age > ? + [18L]")
        void greaterThan() {
            PredicateParser.ParsedPredicate result = PredicateParser.parse("age > 18");

            assertThat(result.sqlFragment()).isEqualTo("age > ?");
            assertThat(result.parameters()).containsExactly(18L);
        }

        @Test
        @DisplayName("大于等于：age >= 18 → age >= ? + [18L]")
        void greaterThanOrEqual() {
            PredicateParser.ParsedPredicate result = PredicateParser.parse("age >= 18");

            assertThat(result.sqlFragment()).isEqualTo("age >= ?");
        }

        @Test
        @DisplayName("小于：price < 99.9 → price < ? + [99.9]")
        void lessThanWithDouble() {
            PredicateParser.ParsedPredicate result = PredicateParser.parse("price < 99.9");

            assertThat(result.sqlFragment()).isEqualTo("price < ?");
            assertThat(result.parameters()).containsExactly(99.9);
        }

        @Test
        @DisplayName("不等于：status != 1 → status != ? + [1L]")
        void notEqual() {
            PredicateParser.ParsedPredicate result = PredicateParser.parse("status != 1");

            assertThat(result.sqlFragment()).isEqualTo("status != ?");
        }

        @Test
        @DisplayName("<> 操作符：status <> 1 → status <> ? + [1L]")
        void angleNotEqual() {
            PredicateParser.ParsedPredicate result = PredicateParser.parse("status <> 1");

            assertThat(result.sqlFragment()).isEqualTo("status <> ?");
        }
    }

    @Nested
    @DisplayName("LIKE 谓词")
    class LikePredicate {

        @Test
        @DisplayName("name LIKE '%张%' → name LIKE ? + [%张%]")
        void likePattern() {
            PredicateParser.ParsedPredicate result = PredicateParser.parse("name LIKE '%张%'");

            assertThat(result.sqlFragment()).isEqualTo("name LIKE ?");
            assertThat(result.parameters()).containsExactly("%张%");
        }

        @Test
        @DisplayName("LIKE 大小写不敏感")
        void likeCaseInsensitive() {
            PredicateParser.ParsedPredicate result = PredicateParser.parse("name like 'abc'");

            assertThat(result.sqlFragment()).isEqualTo("name LIKE ?");
        }
    }

    @Nested
    @DisplayName("IN 谓词")
    class InPredicate {

        @Test
        @DisplayName("id IN (1, 2, 3) → id IN (?, ?, ?) + [1,2,3]")
        void inList() {
            PredicateParser.ParsedPredicate result = PredicateParser.parse("id IN (1, 2, 3)");

            assertThat(result.sqlFragment()).isEqualTo("id IN (?, ?, ?)");
            assertThat(result.parameters()).containsExactly(1L, 2L, 3L);
        }

        @Test
        @DisplayName("name IN ('a', 'b') → name IN (?, ?) + [a,b]")
        void inStringList() {
            PredicateParser.ParsedPredicate result = PredicateParser.parse("name IN ('a', 'b')");

            assertThat(result.sqlFragment()).isEqualTo("name IN (?, ?)");
            assertThat(result.parameters()).containsExactly("a", "b");
        }
    }

    @Nested
    @DisplayName("IS NULL / IS NOT NULL")
    class NullPredicate {

        @Test
        @DisplayName("col IS NULL → 无参数")
        void isNull() {
            PredicateParser.ParsedPredicate result = PredicateParser.parse("col IS NULL");

            assertThat(result.sqlFragment()).isEqualTo("col IS NULL");
            assertThat(result.parameters()).isEmpty();
        }

        @Test
        @DisplayName("col IS NOT NULL → 无参数")
        void isNotNull() {
            PredicateParser.ParsedPredicate result = PredicateParser.parse("col IS NOT NULL");

            assertThat(result.sqlFragment()).isEqualTo("col IS NOT NULL");
            assertThat(result.parameters()).isEmpty();
        }
    }

    @Nested
    @DisplayName("组合谓词 AND / OR")
    class CombinedPredicate {

        @Test
        @DisplayName("AND 连接：id > 100 AND name = 'a' → (id > ? AND name = ?)")
        void andCombination() {
            PredicateParser.ParsedPredicate result = PredicateParser.parse("id > 100 AND name = 'a'");

            assertThat(result.sqlFragment()).isEqualTo("(id > ? AND name = ?)");
            assertThat(result.parameters()).containsExactly(100L, "a");
        }

        @Test
        @DisplayName("OR 连接：status = 1 OR status = 2 → (status = ? OR status = ?)")
        void orCombination() {
            PredicateParser.ParsedPredicate result = PredicateParser.parse("status = 1 OR status = 2");

            assertThat(result.sqlFragment()).isEqualTo("(status = ? OR status = ?)");
            assertThat(result.parameters()).containsExactly(1L, 2L);
        }

        @Test
        @DisplayName("AND + OR 混合：a = 1 AND b = 2 OR c = 3")
        void mixedCombination() {
            PredicateParser.ParsedPredicate result = PredicateParser.parse("a = 1 AND b = 2 OR c = 3");

            assertThat(result.parameters()).containsExactly(1L, 2L, 3L);
        }

        @Test
        @DisplayName("带括号的组合：(a = 1 OR b = 2) AND c = 3")
        void parenthesizedCombination() {
            PredicateParser.ParsedPredicate result = PredicateParser.parse("(a = 1 OR b = 2) AND c = 3");

            assertThat(result.parameters()).containsExactly(1L, 2L, 3L);
        }
    }

    @Nested
    @DisplayName("SQL 注入防护")
    class SqlInjectionGuard {

        @Test
        @DisplayName("拒绝分号注入：1; DROP TABLE users")
        void rejectSemicolonInjection() {
            assertThatThrownBy(() -> PredicateParser.parse("id = 1; DROP TABLE users"))
                    .isInstanceOf(VirtualAdapterException.class)
                    .satisfies(e -> assertThat(((VirtualAdapterException) e).getErrorCode())
                            .isEqualTo("PREDICATE_INVALID"));
        }

        @Test
        @DisplayName("拒绝注释注入：1 -- OR 1=1")
        void rejectCommentInjection() {
            assertThatThrownBy(() -> PredicateParser.parse("id = 1 --"))
                    .isInstanceOf(VirtualAdapterException.class);
        }

        @Test
        @DisplayName("拒绝 UNION 注入：1 UNION SELECT password FROM users")
        void rejectUnionInjection() {
            assertThatThrownBy(() -> PredicateParser.parse("id = 1 UNION SELECT password FROM users"))
                    .isInstanceOf(VirtualAdapterException.class);
        }

        @Test
        @DisplayName("拒绝引号逃逸注入：' OR '1'='1")
        void rejectQuoteEscapeInjection() {
            assertThatThrownBy(() -> PredicateParser.parse("name = '' OR '1'='1'"))
                    .isInstanceOf(VirtualAdapterException.class);
        }

        @Test
        @DisplayName("值中的恶意内容被安全参数化：name = '1; DROP TABLE users'")
        void maliciousValueSafelyParameterized() {
            // 恶意字符串作为值被参数化，不会执行注入
            PredicateParser.ParsedPredicate result =
                    PredicateParser.parse("name = '1; DROP TABLE users'");

            assertThat(result.sqlFragment()).isEqualTo("name = ?");
            assertThat(result.parameters()).containsExactly("1; DROP TABLE users");
        }
    }

    @Nested
    @DisplayName("标识符校验")
    class IdentifierValidation {

        @Test
        @DisplayName("合法列名通过校验")
        void validIdentifier() {
            PredicateParser.validateIdentifier("user_id");
            PredicateParser.validateIdentifier("schema.col");
            PredicateParser.validateIdentifier("_private");
        }

        @Test
        @DisplayName("拒绝含特殊字符的标识符")
        void rejectInvalidIdentifier() {
            assertThatThrownBy(() -> PredicateParser.validateIdentifier("col; DROP"))
                    .isInstanceOf(VirtualAdapterException.class);
            assertThatThrownBy(() -> PredicateParser.validateIdentifier("col' OR'1'='1"))
                    .isInstanceOf(VirtualAdapterException.class);
            assertThatThrownBy(() -> PredicateParser.validateIdentifier(""))
                    .isInstanceOf(VirtualAdapterException.class);
        }
    }
}