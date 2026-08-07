package com.shuqing.bigdata.flinkcdc.kafka;

import java.io.Serializable;
import java.util.Objects;

/**
 * Kafka Topic 命名策略，将源表标识映射为 Kafka Topic 名称。
 *
 * <p>支持三种内置策略：</p>
 * <ul>
 *   <li><b>默认策略</b>：{@code <db>.<schema>.<table>}（无 schema 时退化为 {@code <db>.<table>}）</li>
 *   <li><b>多租户策略</b>：{@code <tenant>.<db>.<schema>.<table>}，租户前缀实现隔离</li>
 *   <li><b>自定义前缀策略</b>：{@code <prefix>.<db>.<schema>.<table>}，可指定任意前缀</li>
 * </ul>
 *
 * <p>Topic 名称约束：</p>
 * <ul>
 *   <li>合法字符：字母、数字、点 {@code .}、下划线 {@code _}、减号 {@code -}</li>
 *   <li>长度上限：249 字符（Kafka 限制）</li>
 *   <li>非空、不以 {@code .} 或 {@code _} 开头/结尾</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
public abstract class TopicNamingStrategy implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Topic 名称最大长度（Kafka 服务端限制 249）。 */
    public static final int MAX_TOPIC_NAME_LENGTH = 249;

    /** 默认 schema 名（当源数据库无 schema 概念时使用，如 MySQL）。 */
    public static final String DEFAULT_SCHEMA = "";

    /**
     * 计算给定源表的 Topic 名称。
     *
     * @param db     数据库名
     * @param schema schema 名；可为 {@code null} 或空（无 schema 概念时）
     * @param table  表名
     * @return Topic 名称
     */
    public abstract String topicName(String db, String schema, String table);

    /**
     * 校验 Topic 名称合法性。
     *
     * @param topic 待校验名称
     * @throws IllegalArgumentException 名称非法
     */
    public static void validate(String topic) {
        if (topic == null || topic.isEmpty()) {
            throw new IllegalArgumentException("Topic 名称不能为空");
        }
        if (topic.length() > MAX_TOPIC_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Topic 名称长度超过上限 " + MAX_TOPIC_NAME_LENGTH + ": " + topic);
        }
        if (topic.charAt(0) == '.' || topic.charAt(0) == '_'
                || topic.charAt(0) == '-') {
            throw new IllegalArgumentException(
                    "Topic 名称不能以 '.'/'_'/'-' 开头: " + topic);
        }
        char last = topic.charAt(topic.length() - 1);
        if (last == '.' || last == '_' || last == '-') {
            throw new IllegalArgumentException(
                    "Topic 名称不能以 '.'/'_'/'-' 结尾: " + topic);
        }
        for (int i = 0; i < topic.length(); i++) {
            char c = topic.charAt(i);
            if (!isValidTopicChar(c)) {
                throw new IllegalArgumentException(
                        "Topic 名称含非法字符 '" + c + "': " + topic);
            }
        }
    }

    /**
     * 判断字符是否为 Topic 名称合法字符。
     *
     * @param c 字符
     * @return 是否合法
     */
    private static boolean isValidTopicChar(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-';
    }

    /**
     * 拼接非空段（跳过 null 和空串）。
     *
     * @param segments 各段
     * @return 以 '.' 拼接的结果
     */
    protected static String join(String... segments) {
        Objects.requireNonNull(segments, "segments 不能为 null");
        StringBuilder sb = new StringBuilder();
        for (String s : segments) {
            if (s == null || s.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('.');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    // ===== 内置策略工厂 =====

    /**
     * 默认策略：{@code <db>.<schema>.<table>}（无 schema 时退化为 {@code <db>.<table>}）。
     *
     * @return 默认策略实例
     */
    public static TopicNamingStrategy defaultStrategy() {
        return new DefaultStrategy();
    }

    /**
     * 多租户策略：{@code <tenant>.<db>.<schema>.<table>}。
     *
     * @param tenant 租户标识（非空）
     * @return 多租户策略实例
     */
    public static TopicNamingStrategy multiTenant(String tenant) {
        if (tenant == null || tenant.isEmpty()) {
            throw new IllegalArgumentException("租户标识不能为空");
        }
        validate(tenant);
        return new MultiTenantStrategy(tenant);
    }

    /**
     * 自定义前缀策略：{@code <prefix>.<db>.<schema>.<table>}。
     *
     * @param prefix 前缀（非空）
     * @return 前缀策略实例
     */
    public static TopicNamingStrategy prefixed(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            throw new IllegalArgumentException("前缀不能为空");
        }
        validate(prefix);
        return new PrefixedStrategy(prefix);
    }

    // ===== 内置策略实现 =====

    /** 默认策略：{@code <db>.<schema>.<table>}。 */
    static final class DefaultStrategy extends TopicNamingStrategy {
        private static final long serialVersionUID = 1L;

        @Override
        public String topicName(String db, String schema, String table) {
            String name = join(db, schema, table);
            validate(name);
            return name;
        }

        @Override
        public String toString() {
            return "TopicNamingStrategy.default";
        }
    }

    /** 多租户策略：{@code <tenant>.<db>.<schema>.<table>}。 */
    static final class MultiTenantStrategy extends TopicNamingStrategy {
        private static final long serialVersionUID = 1L;
        private final String tenant;

        MultiTenantStrategy(String tenant) {
            this.tenant = tenant;
        }

        @Override
        public String topicName(String db, String schema, String table) {
            String name = join(tenant, db, schema, table);
            validate(name);
            return name;
        }

        public String getTenant() {
            return tenant;
        }

        @Override
        public String toString() {
            return "TopicNamingStrategy.multiTenant(tenant=" + tenant + ")";
        }
    }

    /** 自定义前缀策略：{@code <prefix>.<db>.<schema>.<table>}。 */
    static final class PrefixedStrategy extends TopicNamingStrategy {
        private static final long serialVersionUID = 1L;
        private final String prefix;

        PrefixedStrategy(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public String topicName(String db, String schema, String table) {
            String name = join(prefix, db, schema, table);
            validate(name);
            return name;
        }

        public String getPrefix() {
            return prefix;
        }

        @Override
        public String toString() {
            return "TopicNamingStrategy.prefixed(prefix=" + prefix + ")";
        }
    }
}