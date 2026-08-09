package com.levango7.dataenginebdp.flinkcdc.schema;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Confluent Schema Registry 客户端，支持 Schema 注册、版本查询与兼容性检查。
 *
 * <p>核心能力：</p>
 * <ul>
 *   <li>注册 Schema：POST {@code /subjects/<subject>/versions}，返回版本号</li>
 *   <li>获取最新 Schema：GET {@code /subjects/<subject>/versions/latest}</li>
 *   <li>获取指定版本：GET {@code /subjects/<subject>/versions/<version>}</li>
 *   <li>检查兼容性：POST {@code /compatibility/subjects/<subject>/versions/latest}</li>
 *   <li>列出所有 Subject：GET {@code /subjects}</li>
 *   <li>支持 AVRO 与 JSON Schema 两种格式</li>
 * </ul>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * SchemaRegistryClient client = SchemaRegistryClient.builder()
 *     .url("http://127.0.0.1:8081")
 *     .build();
 *
 * int version = client.register("shop.orders-value", schemaJson, SchemaType.AVRO);
 * String latest = client.getLatestSchema("shop.orders-value");
 * boolean compatible = client.checkCompatibility("shop.orders-value", newSchema, SchemaType.AVRO);
 * client.close();
 * }</pre>
 *
 * @author shuqing-bigdata
 */
public final class SchemaRegistryClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistryClient.class);

    /** Schema 类型枚举。 */
    public enum SchemaType {
        /** Avro Schema（Confluent 默认）。 */
        AVRO("AVRO"),
        /** JSON Schema（Confluent 5.5+ 支持）。 */
        JSON("JSON"),
        /** Protobuf Schema（Confluent 6.0+ 支持）。 */
        PROTOBUF("PROTOBUF");

        private final String code;

        SchemaType(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        /**
         * 根据字符串解析为枚举值（大小写不敏感）。
         *
         * @param code 类型编码
         * @return 枚举值
         * @throws IllegalArgumentException 编码不被识别
         */
        public static SchemaType fromCode(String code) {
            Objects.requireNonNull(code, "schema type 不能为 null");
            for (SchemaType t : values()) {
                if (t.code.equalsIgnoreCase(code)) {
                    return t;
                }
            }
            throw new IllegalArgumentException("未知的 schema type: " + code);
        }
    }

    /** 兼容性级别枚举。 */
    public enum CompatibilityLevel {
        NONE("NONE"),
        BACKWARD("BACKWARD"),
        BACKWARD_TRANSITIVE("BACKWARD_TRANSITIVE"),
        FORWARD("FORWARD"),
        FORWARD_TRANSITIVE("FORWARD_TRANSITIVE"),
        FULL("FULL"),
        FULL_TRANSITIVE("FULL_TRANSITIVE");

        private final String code;

        CompatibilityLevel(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final HttpClient httpClient;
    private final String baseUrl;
    private final Duration requestTimeout;
    private final JsonFactory jsonFactory;

    private SchemaRegistryClient(String baseUrl, Duration requestTimeout, HttpClient httpClient) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.requestTimeout = requestTimeout;
        this.httpClient = httpClient;
        this.jsonFactory = new JsonFactory();
    }

    /**
     * 注册 Schema 到指定 Subject，返回新分配的版本号。
     *
     * @param subject Subject 名称（如 {@code shop.orders-value}）
     * @param schema  Schema 字符串（JSON 格式）
     * @param type    Schema 类型
     * @return 版本号（从 1 开始）
     * @throws IOException 注册失败
     */
    public int register(String subject, String schema, SchemaType type) throws IOException {
        Objects.requireNonNull(subject, "subject 不能为 null");
        Objects.requireNonNull(schema, "schema 不能为 null");
        Objects.requireNonNull(type, "type 不能为 null");

        String body = buildRegisterBody(schema, type);
        String url = baseUrl + "/subjects/" + encode(subject) + "/versions";

        HttpResponse<String> resp = sendRequest("POST", url, body);
        if (resp.statusCode() != 200) {
            throw new IOException("注册 Schema 失败: HTTP " + resp.statusCode()
                    + " " + resp.body());
        }
        Map<String, Object> result = parseJson(resp.body());
        Object version = result.get("version");
        if (version instanceof Number n) {
            log.info("注册 Schema: subject={}, version={}", subject, n.intValue());
            return n.intValue();
        }
        throw new IOException("Schema Registry 返回异常: " + resp.body());
    }

    /**
     * 获取 Subject 的最新 Schema。
     *
     * @param subject Subject 名称
     * @return Schema 字符串
     * @throws IOException 获取失败
     */
    public String getLatestSchema(String subject) throws IOException {
        Objects.requireNonNull(subject, "subject 不能为 null");
        String url = baseUrl + "/subjects/" + encode(subject) + "/versions/latest";
        HttpResponse<String> resp = sendRequest("GET", url, null);
        if (resp.statusCode() != 200) {
            throw new IOException("获取最新 Schema 失败: HTTP " + resp.statusCode()
                    + " " + resp.body());
        }
        Map<String, Object> result = parseJson(resp.body());
        Object schema = result.get("schema");
        return schema == null ? null : String.valueOf(schema);
    }

    /**
     * 获取 Subject 指定版本的 Schema。
     *
     * @param subject Subject 名称
     * @param version 版本号（从 1 开始）
     * @return Schema 字符串
     * @throws IOException 获取失败
     */
    public String getSchema(String subject, int version) throws IOException {
        Objects.requireNonNull(subject, "subject 不能为 null");
        if (version <= 0) {
            throw new IllegalArgumentException("版本号必须为正: " + version);
        }
        String url = baseUrl + "/subjects/" + encode(subject) + "/versions/" + version;
        HttpResponse<String> resp = sendRequest("GET", url, null);
        if (resp.statusCode() != 200) {
            throw new IOException("获取 Schema 失败: HTTP " + resp.statusCode()
                    + " " + resp.body());
        }
        Map<String, Object> result = parseJson(resp.body());
        Object schema = result.get("schema");
        return schema == null ? null : String.valueOf(schema);
    }

    /**
     * 检查给定 Schema 与 Subject 最新版本的兼容性。
     *
     * @param subject Subject 名称
     * @param schema  待检查 Schema
     * @param type    Schema 类型
     * @return {@code true} 兼容；{@code false} 不兼容
     * @throws IOException 请求失败
     */
    public boolean checkCompatibility(String subject, String schema, SchemaType type) throws IOException {
        Objects.requireNonNull(subject, "subject 不能为 null");
        Objects.requireNonNull(schema, "schema 不能为 null");
        Objects.requireNonNull(type, "type 不能为 null");

        String body = buildRegisterBody(schema, type);
        String url = baseUrl + "/compatibility/subjects/" + encode(subject) + "/versions/latest";

        HttpResponse<String> resp = sendRequest("POST", url, body);
        if (resp.statusCode() != 200) {
            throw new IOException("兼容性检查失败: HTTP " + resp.statusCode()
                    + " " + resp.body());
        }
        Map<String, Object> result = parseJson(resp.body());
        Object compatible = result.get("compatible");
        if (compatible instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(compatible));
    }

    /**
     * 设置 Subject 的全局兼容性级别。
     *
     * @param subject Subject 名称；{@code null} 表示设置全局配置
     * @param level   兼容性级别
     * @return 是否设置成功
     * @throws IOException 请求失败
     */
    public boolean setCompatibility(String subject, CompatibilityLevel level) throws IOException {
        Objects.requireNonNull(level, "compatibility level 不能为 null");
        String body = "{\"compatibility\":\"" + level.code() + "\"}";
        String url = subject == null
                ? baseUrl + "/config"
                : baseUrl + "/config/" + encode(subject);
        HttpResponse<String> resp = sendRequest("PUT", url, body);
        if (resp.statusCode() != 200) {
            throw new IOException("设置兼容性失败: HTTP " + resp.statusCode()
                    + " " + resp.body());
        }
        return true;
    }

    /**
     * 列出所有 Subject。
     *
     * @return Subject 名称列表
     * @throws IOException 请求失败
     */
    public java.util.List<String> listSubjects() throws IOException {
        String url = baseUrl + "/subjects";
        HttpResponse<String> resp = sendRequest("GET", url, null);
        if (resp.statusCode() != 200) {
            throw new IOException("列出 Subject 失败: HTTP " + resp.statusCode()
                    + " " + resp.body());
        }
        return parseJsonArray(resp.body()).stream()
                .map(Object::toString).toList();
    }

    /**
     * 获取 Subject 的所有版本号。
     *
     * @param subject Subject 名称
     * @return 版本号列表
     * @throws IOException 请求失败
     */
    public java.util.List<Integer> listVersions(String subject) throws IOException {
        Objects.requireNonNull(subject, "subject 不能为 null");
        String url = baseUrl + "/subjects/" + encode(subject) + "/versions";
        HttpResponse<String> resp = sendRequest("GET", url, null);
        if (resp.statusCode() != 200) {
            throw new IOException("列出版本失败: HTTP " + resp.statusCode()
                    + " " + resp.body());
        }
        java.util.List<Integer> versions = new java.util.ArrayList<>();
        for (Object o : parseJsonArray(resp.body())) {
            if (o instanceof Number n) {
                versions.add(n.intValue());
            } else {
                versions.add(Integer.parseInt(String.valueOf(o)));
            }
        }
        return versions;
    }

    /**
     * 删除 Subject 的所有版本（慎用）。
     *
     * @param subject Subject 名称
     * @return 是否删除成功
     * @throws IOException 请求失败
     */
    public boolean deleteSubject(String subject) throws IOException {
        Objects.requireNonNull(subject, "subject 不能为 null");
        String url = baseUrl + "/subjects/" + encode(subject);
        HttpResponse<String> resp = sendRequest("DELETE", url, null);
        if (resp.statusCode() != 200) {
            throw new IOException("删除 Subject 失败: HTTP " + resp.statusCode()
                    + " " + resp.body());
        }
        log.info("删除 Subject: {}", subject);
        return true;
    }

    // ===== 内部辅助 =====

    /**
     * 构造注册请求体。
     *
     * @param schema Schema 字符串
     * @param type   Schema 类型
     * @return JSON 请求体
     */
    static String buildRegisterBody(String schema, SchemaType type) {
        // 转义 schema 中的特殊字符
        String escaped = escapeJson(schema);
        return "{\"schema\":\"" + escaped + "\",\"schemaType\":\"" + type.code() + "\"}";
    }

    /**
     * 发送 HTTP 请求。
     *
     * @param method HTTP 方法
     * @param url    URL
     * @param body   请求体（GET 时为 null）
     * @return HTTP 响应
     * @throws IOException 请求失败
     */
    private HttpResponse<String> sendRequest(String method, String url, String body) throws IOException {
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(requestTimeout)
                    .header("Accept", "application/vnd.schemaregistry.v1+json");
            if (body != null) {
                reqBuilder.header("Content-Type", "application/vnd.schemaregistry.v1+json");
                reqBuilder.method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                reqBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            return httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP 请求被中断: " + method + " " + url, e);
        }
    }

    /**
     * URL 编码 subject 名（处理特殊字符如 '-'）。
     *
     * @param s 待编码字符串
     * @return 编码后字符串
     */
    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * 解析 JSON 对象为 Map。
     *
     * @param json JSON 字符串
     * @return Map
     * @throws IOException 解析失败
     */
    Map<String, Object> parseJson(String json) throws IOException {
        try (JsonParser parser = jsonFactory.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("期望 JSON 对象");
            }
            return parseJsonObject(parser);
        }
    }

    /**
     * 解析 JSON 数组为 List。
     *
     * @param json JSON 字符串
     * @return List
     * @throws IOException 解析失败
     */
    java.util.List<Object> parseJsonArray(String json) throws IOException {
        try (JsonParser parser = jsonFactory.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IOException("期望 JSON 数组");
            }
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
    }

    private static Map<String, Object> parseJsonObject(JsonParser parser) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            JsonToken token = parser.nextToken();
            if (token == JsonToken.END_OBJECT) {
                break;
            }
            if (token != JsonToken.FIELD_NAME) {
                throw new IOException("期望字段名");
            }
            String fieldName = parser.getCurrentName();
            token = parser.nextToken();
            map.put(fieldName, parseJsonValue(parser, token));
        }
        return map;
    }

    private static Object parseJsonValue(JsonParser parser, JsonToken token) throws IOException {
        return switch (token) {
            case VALUE_NULL -> null;
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_STRING -> parser.getValueAsString();
            case VALUE_NUMBER_INT -> parser.getLongValue();
            case VALUE_NUMBER_FLOAT -> parser.getDoubleValue();
            case START_OBJECT -> parseJsonObject(parser);
            case START_ARRAY -> {
                java.util.List<Object> list = new java.util.ArrayList<>();
                while (true) {
                    JsonToken t = parser.nextToken();
                    if (t == JsonToken.END_ARRAY) {
                        break;
                    }
                    list.add(parseJsonValue(parser, t));
                }
                yield list;
            }
            default -> throw new IOException("意外的 JSON token: " + token);
        };
    }

    /**
     * 转义 JSON 字符串中的特殊字符。
     *
     * @param s 原始字符串
     * @return 转义后字符串
     */
    private static String escapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
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

    @Override
    public void close() {
        // HttpClient 复用，无需显式关闭
        log.debug("SchemaRegistryClient 已关闭");
    }

    /**
     * 创建 Builder。
     *
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * SchemaRegistryClient 构造器。
     */
    public static final class Builder {
        private String url = "http://localhost:8081";
        private Duration requestTimeout = Duration.ofSeconds(30);
        private HttpClient httpClient;

        /** Schema Registry URL。 */
        public Builder url(String url) {
            this.url = Objects.requireNonNull(url);
            return this;
        }

        /** 请求超时时间。 */
        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = Objects.requireNonNull(timeout);
            return this;
        }

        /** 注入外部 HttpClient（用于测试）。 */
        Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /** 构建 SchemaRegistryClient。 */
        public SchemaRegistryClient build() {
            HttpClient client = this.httpClient;
            if (client == null) {
                client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
            }
            return new SchemaRegistryClient(url, requestTimeout, client);
        }
    }
}