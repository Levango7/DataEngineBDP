package com.shuqing.bigdata.flinkcdc;

import com.shuqing.bigdata.flinkcdc.model.ChangeRecord;

import java.util.Map;
import java.util.Objects;

/**
 * 将 {@link ChangeRecord} 序列化为 Debezium JSON 格式的字节数组。
 *
 * <p>采用轻量手写实现，避免对 Jackson 的强依赖，同时保证 Flink 集群运行时
 * 无需额外序列化器配置。输出严格遵循 Debezium JSON 结构：</p>
 *
 * <pre>{@code
 * {"before":{...},"after":{...},"op":"u","source":{...},"ts_ms":1700000000000}
 * }</pre>
 *
 * @author shuqing-bigdata
 */
public final class ChangeRecordJsonSerializer {

    private ChangeRecordJsonSerializer() {
        // 工具类，禁止实例化
    }

    /**
     * 将 ChangeRecord 序列化为 Debezium JSON 字节数组（UTF-8）。
     *
     * @param record 变更记录
     * @return JSON 字节数组
     * @throws NullPointerException 若 record 为 null
     */
    public static byte[] toJson(ChangeRecord record) {
        Objects.requireNonNull(record, "ChangeRecord 不能为 null");
        return toJsonString(record).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 将 ChangeRecord 序列化为 Debezium JSON 字符串。
     *
     * @param record 变更记录
     * @return JSON 字符串
     */
    public static String toJsonString(ChangeRecord record) {
        Objects.requireNonNull(record, "ChangeRecord 不能为 null");
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendField(sb, "before", record.getBefore(), true);
        sb.append(',');
        appendField(sb, "after", record.getAfter(), true);
        sb.append(',');
        appendKeyValue(sb, "op", record.getOp(), true);
        sb.append(',');
        appendField(sb, "source", record.getSource(), true);
        sb.append(',');
        appendKeyValue(sb, "ts_ms", record.getTsMs() == null ? null : record.getTsMs().toString(), false);
        sb.append('}');
        return sb.toString();
    }

    /**
     * 追加一个键值对（值为字符串时加引号，否则原样输出）。
     *
     * @param sb       字符串构建器
     * @param key      键名
     * @param value    值
     * @param quoted   值是否需要引号
     */
    private static void appendKeyValue(StringBuilder sb, String key, String value, boolean quoted) {
        sb.append('"').append(key).append("\":");
        if (value == null) {
            sb.append("null");
        } else if (quoted) {
            sb.append('"').append(escapeJson(value)).append('"');
        } else {
            sb.append(value);
        }
    }

    /**
     * 追加一个对象字段（Map 序列化为 JSON 对象）。
     *
     * @param sb       字符串构建器
     * @param key      键名
     * @param value    Map 值
     * @param first    是否可能为首个字段（未使用，保留以保持签名一致）
     */
    private static void appendField(StringBuilder sb, String key, Map<String, Object> value, boolean first) {
        sb.append('"').append(key).append("\":");
        if (value == null) {
            sb.append("null");
        } else {
            appendMap(sb, value);
        }
    }

    /**
     * 将 Map 序列化为 JSON 对象。
     *
     * @param sb 字符串构建器
     * @param map Map
     */
    private static void appendMap(StringBuilder sb, Map<String, Object> map) {
        sb.append('{');
        boolean firstEntry = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!firstEntry) {
                sb.append(',');
            }
            firstEntry = false;
            sb.append('"').append(escapeJson(entry.getKey())).append("\":");
            appendValue(sb, entry.getValue());
        }
        sb.append('}');
    }

    /**
     * 追加一个值（根据类型选择 JSON 表示）。
     *
     * @param sb    字符串构建器
     * @param value 值
     */
    private static void appendValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            sb.append('"').append(escapeJson(String.valueOf(value))).append('"');
        }
    }

    /**
     * 转义 JSON 字符串中的特殊字符。
     *
     * @param s 原始字符串
     * @return 转义后的字符串
     */
    private static String escapeJson(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}