package com.shuqing.bigdata.flinkcdc.debezium;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.shuqing.bigdata.flinkcdc.model.ChangeRecord;
import com.ververica.cdc.debezium.DebeziumDeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.util.Collector;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Debezium 事件反序列化器，将 Debezium 事件转换为 {@link DebeziumChangeRecord}。
 *
 * <p>支持两种输入形态：</p>
 * <ul>
 *   <li><b>SourceRecord (Struct)</b> — 来自 Flink CDC 3.0 内部 Debezium Engine，
 *       value 为 Kafka Connect {@link Struct}，含 before/after/op/source/ts_ms 字段。
 *       通过 {@link #deserialize(SourceRecord, Collector)} 调用。</li>
 *   <li><b>JSON 字节数组</b> — 来自 Kafka Topic 中已落地的 Debezium JSON 消息，
 *       通过 {@link #deserializeJson(byte[])} 解析。</li>
 * </ul>
 *
 * <p>解析后输出 {@link DebeziumChangeRecord}，保留 schema/sourceMeta/transaction 等扩展元数据。
 * 支持与 Schema Registry 集成（外部传入 {@link SchemaResolver} 时调用解析 schema 信息）。</p>
 *
 * <p>异常处理策略：</p>
 * <ul>
 *   <li>{@code null} value（tombstone 消息）— 跳过，不输出</li>
 *   <li>op 字段缺失或无法识别 — 抛出 {@link IllegalArgumentException}</li>
 *   <li>JSON 格式错误 — 抛出 {@link IOException}</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
public final class DebeziumDeserializer
        implements DebeziumDeserializationSchema<ChangeRecord>, Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(DebeziumDeserializer.class);

    /** Debezium value 结构中的字段名常量。 */
    public static final String FIELD_BEFORE = "before";
    public static final String FIELD_AFTER = "after";
    public static final String FIELD_OP = "op";
    public static final String FIELD_SOURCE = "source";
    public static final String FIELD_TS_MS = "ts_ms";
    public static final String FIELD_SCHEMA = "schema";
    public static final String FIELD_PAYLOAD = "payload";
    public static final String FIELD_TRANSACTION = "transaction";

    /** 可选的 Schema 解析器（用于 Schema Registry 集成）。 */
    private final SchemaResolver schemaResolver;

    /** 复用的 JsonFactory（线程安全）。 */
    private transient JsonFactory jsonFactory;

    /** 默认构造：不启用 Schema Registry。 */
    public DebeziumDeserializer() {
        this(null);
    }

    /**
     * 构造并指定 Schema 解析器。
     *
     * @param schemaResolver Schema 解析器；{@code null} 表示不启用
     */
    public DebeziumDeserializer(SchemaResolver schemaResolver) {
        this.schemaResolver = schemaResolver;
    }

    /**
     * 从 Flink CDC 3.0 的 {@link SourceRecord} 反序列化。
     *
     * @param record Debezium SourceRecord
     * @param out    输出收集器
     */
    @Override
    public void deserialize(SourceRecord record, Collector<ChangeRecord> out) {
        Objects.requireNonNull(record, "SourceRecord 不能为 null");
        Object valueObj = record.value();
        if (valueObj == null) {
            // tombstone 记录（DELETE 后 Debezium 会发送 value=null 的墓碑消息），跳过
            log.debug("跳过 tombstone 记录: topic={}, sourceOffset={}",
                    record.topic(), record.sourceOffset());
            return;
        }
        if (!(valueObj instanceof Struct value)) {
            log.warn("跳过非 Struct 类型记录: {}", valueObj.getClass());
            return;
        }

        DebeziumChangeRecord change = parseStruct(value);
        out.collect(change);
    }

    @Override
    public TypeInformation<ChangeRecord> getProducedType() {
        return TypeInformation.of(ChangeRecord.class);
    }

    /**
     * 从 Debezium JSON 字节数组解析为 {@link DebeziumChangeRecord}。
     *
     * <p>支持两种 JSON 形态：</p>
     * <ul>
     *   <li><b>扁平格式</b>：{@code {"before":...,"after":...,"op":"u","source":...,"ts_ms":...}}
     *       — 直接位于根对象</li>
     *   <li><b>包装格式</b>：{@code {"schema":...,"payload":{"before":...,"after":...}}}
     *       — 字段位于 {@code payload} 内，{@code schema} 单独提取</li>
     * </ul>
     *
     * @param jsonBytes JSON 字节数组
     * @return 解析后的 DebeziumChangeRecord
     * @throws IOException JSON 解析失败
     * @throws IllegalArgumentException 必要字段缺失或 op 无法识别
     */
    public DebeziumChangeRecord deserializeJson(byte[] jsonBytes) throws IOException {
        Objects.requireNonNull(jsonBytes, "jsonBytes 不能为 null");
        if (jsonBytes.length == 0) {
            throw new IllegalArgumentException("JSON 字节数组为空");
        }
        JsonFactory factory = getJsonFactory();
        try (JsonParser parser = factory.createParser(jsonBytes)) {
            return parseJson(parser);
        }
    }

    /**
     * 从 JSON 字符串解析。
     *
     * @param json JSON 字符串
     * @return DebeziumChangeRecord
     * @throws IOException 解析失败
     */
    public DebeziumChangeRecord deserializeJson(String json) throws IOException {
        Objects.requireNonNull(json, "json 不能为 null");
        return deserializeJson(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ===== 内部解析方法 =====

    /**
     * 解析 Kafka Connect Struct 为 DebeziumChangeRecord。
     *
     * @param value Debezium value Struct
     * @return DebeziumChangeRecord
     */
    DebeziumChangeRecord parseStruct(Struct value) {
        Map<String, Object> before = extractStruct(value, FIELD_BEFORE);
        Map<String, Object> after = extractStruct(value, FIELD_AFTER);
        String opCode = requireString(value, FIELD_OP);
        // 校验 op 编码合法
        validateOpCode(opCode);
        Map<String, Object> source = extractStruct(value, FIELD_SOURCE);
        Long tsMs = value.getInt64(FIELD_TS_MS);

        // 提取 transaction 元数据（若存在）
        Map<String, Object> transaction = extractStruct(value, FIELD_TRANSACTION);

        // 提取 source 扩展字段（snapshot / dbserver_name / lsn 等）
        Map<String, Object> sourceMeta = extractSourceMeta(source);

        // schema 信息（可选）
        Map<String, Object> schemaInfo = null;
        if (schemaResolver != null) {
            schemaInfo = schemaResolver.resolve(value);
        }

        return new DebeziumChangeRecord(before, after, opCode, source, tsMs,
                schemaInfo, sourceMeta, transaction);
    }

    /**
     * 解析 JSON 流。
     *
     * @param parser Jackson JsonParser
     * @return DebeziumChangeRecord
     * @throws IOException 解析失败
     */
    DebeziumChangeRecord parseJson(JsonParser parser) throws IOException {
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            throw new IOException("期望 JSON 对象起始，实际: " + parser.currentToken());
        }

        Map<String, Object> root = parseJsonObject(parser);

        // 判断是否为包装格式（含 payload 字段）
        Map<String, Object> payload;
        Map<String, Object> schemaInfo;
        Object payloadObj = root.get(FIELD_PAYLOAD);
        Object schemaObj = root.get(FIELD_SCHEMA);
        if (payloadObj instanceof Map<?, ?> p) {
            @SuppressWarnings("unchecked")
            Map<String, Object> payloadMap = (Map<String, Object>) p;
            payload = payloadMap;
        } else {
            payload = root;
        }
        if (schemaObj instanceof Map<?, ?> s) {
            @SuppressWarnings("unchecked")
            Map<String, Object> schemaMap = (Map<String, Object>) s;
            schemaInfo = schemaMap;
        } else {
            schemaInfo = null;
        }

        // 必要字段校验
        Object opObj = payload.get(FIELD_OP);
        if (opObj == null) {
            throw new IllegalArgumentException("Debezium JSON 缺少必要字段: op");
        }
        String opCode = String.valueOf(opObj);
        validateOpCode(opCode);

        @SuppressWarnings("unchecked")
        Map<String, Object> before = (Map<String, Object>) payload.get(FIELD_BEFORE);
        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) payload.get(FIELD_AFTER);
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) payload.get(FIELD_SOURCE);
        @SuppressWarnings("unchecked")
        Map<String, Object> transaction = (Map<String, Object>) payload.get(FIELD_TRANSACTION);

        Long tsMs = null;
        Object tsObj = payload.get(FIELD_TS_MS);
        if (tsObj instanceof Number n) {
            tsMs = n.longValue();
        } else if (tsObj != null) {
            tsMs = Long.parseLong(String.valueOf(tsObj));
        }

        Map<String, Object> sourceMeta = extractSourceMeta(source);

        return new DebeziumChangeRecord(before, after, opCode, source, tsMs,
                schemaInfo, sourceMeta, transaction);
    }

    /**
     * 校验 op 编码合法。
     *
     * @param opCode op 编码
     * @throws IllegalArgumentException 若编码不被识别
     */
    private static void validateOpCode(String opCode) {
        if (opCode == null || opCode.isEmpty()) {
            throw new IllegalArgumentException("Debezium op 字段为空");
        }
        switch (opCode) {
            case "c", "u", "d", "r" -> { /* 合法 */ }
            default -> throw new IllegalArgumentException("未知的 Debezium op 编码: " + opCode);
        }
    }

    /**
     * 从 Struct 中提取字段为 Map。
     *
     * @param value     Debezium value Struct
     * @param fieldName 字段名
     * @return Map；若字段为 null 返回 {@code null}
     */
    private static Map<String, Object> extractStruct(Struct value, String fieldName) {
        Schema schema = value.schema();
        if (schema.field(fieldName) == null) {
            return null;
        }
        Struct sub = value.getStruct(fieldName);
        if (sub == null) {
            return null;
        }
        Schema subSchema = sub.schema();
        Map<String, Object> map = new LinkedHashMap<>();
        for (Field field : subSchema.fields()) {
            map.put(field.name(), sub.get(field));
        }
        return map;
    }

    /**
     * 提取必填字符串字段。
     *
     * @param value     Struct
     * @param fieldName 字段名
     * @return 字符串值
     * @throws IllegalArgumentException 字段缺失
     */
    private static String requireString(Struct value, String fieldName) {
        Schema schema = value.schema();
        if (schema.field(fieldName) == null) {
            throw new IllegalArgumentException("Debezium Struct 缺少必要字段: " + fieldName);
        }
        Object v = value.get(fieldName);
        if (v == null) {
            throw new IllegalArgumentException("Debezium 字段 " + fieldName + " 为 null");
        }
        return String.valueOf(v);
    }

    /**
     * 从 source 元数据中提取扩展字段（snapshot / dbserver_name / lsn / txId / thread）。
     *
     * @param source source 元数据
     * @return 扩展字段 Map；若 source 为 null 返回 {@code null}
     */
    private static Map<String, Object> extractSourceMeta(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        // 已知的扩展字段集合
        String[] knownMetaKeys = {"snapshot", "dbserver_name", "lsn", "txId", "thread",
                "ts_ns", "ts_us", "ts_sec", "snapshot=true"};
        for (String key : knownMetaKeys) {
            Object v = source.get(key);
            if (v != null) {
                meta.put(key, v);
            }
        }
        return meta.isEmpty() ? null : meta;
    }

    /**
     * 解析 JSON 对象为 Map（递归）。
     *
     * @param parser JsonParser
     * @return Map
     * @throws IOException 解析失败
     */
    private static Map<String, Object> parseJsonObject(JsonParser parser) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            JsonToken token = parser.nextToken();
            if (token == JsonToken.END_OBJECT) {
                break;
            }
            if (token != JsonToken.FIELD_NAME) {
                throw new IOException("期望字段名，实际: " + token);
            }
            String fieldName = parser.getCurrentName();
            token = parser.nextToken();
            map.put(fieldName, parseJsonValue(parser, token));
        }
        return map;
    }

    /**
     * 解析 JSON 值（根据当前 token 决定类型）。
     *
     * @param parser JsonParser
     * @param token  当前 token
     * @return Java 对象
     * @throws IOException 解析失败
     */
    private static Object parseJsonValue(JsonParser parser, JsonToken token) throws IOException {
        return switch (token) {
            case VALUE_NULL -> null;
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_STRING -> parser.getValueAsString();
            case VALUE_NUMBER_INT -> parser.getLongValue();
            case VALUE_NUMBER_FLOAT -> parser.getDoubleValue();
            case START_OBJECT -> parseJsonObject(parser);
            case START_ARRAY -> parseJsonArray(parser);
            default -> throw new IOException("意外的 JSON token: " + token);
        };
    }

    /**
     * 解析 JSON 数组为 List。
     *
     * @param parser JsonParser
     * @return List
     * @throws IOException 解析失败
     */
    private static java.util.List<Object> parseJsonArray(JsonParser parser) throws IOException {
        java.util.List<Object> list = new java.util.ArrayList<>();
        while (true) {
            JsonToken token = parser.nextToken();
            if (token == JsonToken.END_ARRAY) {
                break;
            }
            list.add(parseJsonValue(parser, token));
        }
        return list;
    }

    /**
     * 获取 JsonFactory（懒初始化，复用）。
     *
     * @return JsonFactory
     */
    private JsonFactory getJsonFactory() {
        if (jsonFactory == null) {
            jsonFactory = new JsonFactory();
        }
        return jsonFactory;
    }

    /**
     * Schema 解析器接口，用于从 Debezium 事件中提取 schema 信息（如对接 Schema Registry）。
     *
     * @author shuqing-bigdata
     */
    @FunctionalInterface
    public interface SchemaResolver extends Serializable {
        /**
         * 从 Debezium value Struct 解析 schema 信息。
         *
         * @param value Debezium value
         * @return schema 描述 Map
         */
        Map<String, Object> resolve(Struct value);
    }
}