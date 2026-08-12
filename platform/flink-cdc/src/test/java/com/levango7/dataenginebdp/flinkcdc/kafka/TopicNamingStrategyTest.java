package com.levango7.dataenginebdp.flinkcdc.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TopicNamingStrategy} 单元测试。
 *
 * @author shuqing-bigdata
 */
class TopicNamingStrategyTest {

    @Nested
    @DisplayName("默认策略: <db>.<schema>.<table>")
    class DefaultStrategyTest {

        @Test
        @DisplayName("含 schema — 输出 db.schema.table")
        void withSchema() {
            TopicNamingStrategy strategy = TopicNamingStrategy.defaultStrategy();
            assertThat(strategy.topicName("shop", "dbo", "orders"))
                    .isEqualTo("shop.dbo.orders");
        }

        @Test
        @DisplayName("无 schema — 退化为 db.table")
        void withoutSchema() {
            TopicNamingStrategy strategy = TopicNamingStrategy.defaultStrategy();
            assertThat(strategy.topicName("shop", null, "orders"))
                    .isEqualTo("shop.orders");
        }

        @Test
        @DisplayName("空 schema — 退化为 db.table")
        void emptySchema() {
            TopicNamingStrategy strategy = TopicNamingStrategy.defaultStrategy();
            assertThat(strategy.topicName("shop", "", "orders"))
                    .isEqualTo("shop.orders");
        }

        @Test
        @DisplayName("toString — 包含 default 标识")
        void toString_test() {
            TopicNamingStrategy strategy = TopicNamingStrategy.defaultStrategy();
            assertThat(strategy.toString()).contains("default");
        }
    }

    @Nested
    @DisplayName("多租户策略: <tenant>.<db>.<schema>.<table>")
    class MultiTenantStrategyTest {

        @Test
        @DisplayName("含 schema — 输出 tenant.db.schema.table")
        void withSchema() {
            TopicNamingStrategy strategy = TopicNamingStrategy.multiTenant("tenant-a");
            assertThat(strategy.topicName("shop", "dbo", "orders"))
                    .isEqualTo("tenant-a.shop.dbo.orders");
        }

        @Test
        @DisplayName("无 schema — 输出 tenant.db.table")
        void withoutSchema() {
            TopicNamingStrategy strategy = TopicNamingStrategy.multiTenant("tenant-a");
            assertThat(strategy.topicName("shop", null, "orders"))
                    .isEqualTo("tenant-a.shop.orders");
        }

        @Test
        @DisplayName("不同租户 — Topic 名称不同")
        void differentTenants() {
            TopicNamingStrategy s1 = TopicNamingStrategy.multiTenant("tenant-a");
            TopicNamingStrategy s2 = TopicNamingStrategy.multiTenant("tenant-b");

            String t1 = s1.topicName("shop", null, "orders");
            String t2 = s2.topicName("shop", null, "orders");

            assertThat(t1).isEqualTo("tenant-a.shop.orders");
            assertThat(t2).isEqualTo("tenant-b.shop.orders");
            assertThat(t1).isNotEqualTo(t2);
        }

        @Test
        @DisplayName("空租户 — 抛出异常")
        void emptyTenant() {
            assertThatThrownBy(() -> TopicNamingStrategy.multiTenant(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null 租户 — 抛出异常")
        void nullTenant() {
            assertThatThrownBy(() -> TopicNamingStrategy.multiTenant(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("toString — 包含租户标识")
        void toString_test() {
            TopicNamingStrategy strategy = TopicNamingStrategy.multiTenant("tenant-a");
            assertThat(strategy.toString()).contains("tenant-a");
        }
    }

    @Nested
    @DisplayName("前缀策略: <prefix>.<db>.<schema>.<table>")
    class PrefixedStrategyTest {

        @Test
        @DisplayName("含 schema — 输出 prefix.db.schema.table")
        void withSchema() {
            TopicNamingStrategy strategy = TopicNamingStrategy.prefixed("cdc");
            assertThat(strategy.topicName("shop", "dbo", "orders"))
                    .isEqualTo("cdc.shop.dbo.orders");
        }

        @Test
        @DisplayName("无 schema — 输出 prefix.db.table")
        void withoutSchema() {
            TopicNamingStrategy strategy = TopicNamingStrategy.prefixed("cdc");
            assertThat(strategy.topicName("shop", null, "orders"))
                    .isEqualTo("cdc.shop.orders");
        }

        @Test
        @DisplayName("空前缀 — 抛出异常")
        void emptyPrefix() {
            assertThatThrownBy(() -> TopicNamingStrategy.prefixed(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null 前缀 — 抛出异常")
        void nullPrefix() {
            assertThatThrownBy(() -> TopicNamingStrategy.prefixed(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Topic 名称校验")
    class ValidationTest {

        @Test
        @DisplayName("合法名称 — 通过校验")
        void validNames() {
            TopicNamingStrategy.validate("shop.orders");
            TopicNamingStrategy.validate("tenant-a.shop.dbo.orders");
            TopicNamingStrategy.validate("cdc_shop_orders");
            TopicNamingStrategy.validate("topic-1");
        }

        @Test
        @DisplayName("空名称 — 抛出异常")
        void emptyName() {
            assertThatThrownBy(() -> TopicNamingStrategy.validate(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null 名称 — 抛出异常")
        void nullName() {
            assertThatThrownBy(() -> TopicNamingStrategy.validate(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("以点开头 — 抛出异常")
        void startsWithDot() {
            assertThatThrownBy(() -> TopicNamingStrategy.validate(".invalid"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("以点结尾 — 抛出异常")
        void endsWithDot() {
            assertThatThrownBy(() -> TopicNamingStrategy.validate("invalid."))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("含非法字符 — 抛出异常")
        void illegalChar() {
            assertThatThrownBy(() -> TopicNamingStrategy.validate("shop@orders"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> TopicNamingStrategy.validate("shop/orders"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("超长名称 — 抛出异常")
        void tooLong() {
            String name = "a".repeat(TopicNamingStrategy.MAX_TOPIC_NAME_LENGTH + 1);
            assertThatThrownBy(() -> TopicNamingStrategy.validate(name))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("最大长度名称 — 通过校验")
        void maxLength() {
            String name = "a".repeat(TopicNamingStrategy.MAX_TOPIC_NAME_LENGTH);
            TopicNamingStrategy.validate(name);
        }
    }

    @Nested
    @DisplayName("多租户隔离验证")
    class TenantIsolationTest {

        @Test
        @DisplayName("相同表名不同租户 — Topic 完全隔离")
        void sameTableDifferentTenants() {
            TopicNamingStrategy t1 = TopicNamingStrategy.multiTenant("tenant-a");
            TopicNamingStrategy t2 = TopicNamingStrategy.multiTenant("tenant-b");

            String topic1 = t1.topicName("shop", null, "orders");
            String topic2 = t2.topicName("shop", null, "orders");

            assertThat(topic1).startsWith("tenant-a.");
            assertThat(topic2).startsWith("tenant-b.");
            assertThat(topic1).isNotEqualTo(topic2);
        }

        @Test
        @DisplayName("相同租户不同表 — Topic 不同")
        void sameTenantDifferentTables() {
            TopicNamingStrategy strategy = TopicNamingStrategy.multiTenant("tenant-a");

            String t1 = strategy.topicName("shop", null, "orders");
            String t2 = strategy.topicName("shop", null, "products");

            assertThat(t1).isNotEqualTo(t2);
        }

        @Test
        @DisplayName("相同租户不同库 — Topic 不同")
        void sameTenantDifferentDbs() {
            TopicNamingStrategy strategy = TopicNamingStrategy.multiTenant("tenant-a");

            String t1 = strategy.topicName("shop", null, "orders");
            String t2 = strategy.topicName("inventory", null, "orders");

            assertThat(t1).isNotEqualTo(t2);
        }
    }
}