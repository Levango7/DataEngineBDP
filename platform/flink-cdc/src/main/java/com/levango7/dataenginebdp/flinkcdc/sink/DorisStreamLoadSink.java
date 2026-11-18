package com.levango7.dataenginebdp.flinkcdc.sink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.flinkcdc.model.ChangeRecord;
import org.apache.flink.api.connector.sink2.Sink;
import org.apache.flink.api.connector.sink2.SinkWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Doris Stream Load Sink（CDC→Doris 入湖，实时链路补齐专项 Phase B1）。
 *
 * <p>通过 Doris FE 的 Stream Load HTTP 接口按批写入：{@code PUT /api/{db}/{table}/_stream_load}，
 * Basic 认证 + {@code format: json} + {@code strip_outer_array: true}（请求体为 JSON 数组）。
 * 依赖面仅 JDK {@link HttpClient} 与 Jackson，不引入 Doris 官方 Flink Connector（重依赖）。</p>
 *
 * <p><b>行形态</b>：数组每行＝**打平后的业务列对象**（{@code c/r/u} 取 after、{@code d} 取
 * before）——不是 ChangeRecord 的包裹序列化；详见 {@code toRowJson}。
 * <b>删除语义</b>：{@code d} 行不随普通批（4.x 普通导入会忽略
 * {@code __DORIS_DELETE_SIGN__} 并整行 upsert 成 NULL 版本——端到端实证），
 * 而是独立成批发 {@code merge_type: MERGE} + {@code delete:} 条件
 * （条件＝before 全键，pgoutput 的 before 只含主键列）；详见
 * {@code buildDeleteCondition} / {@code flushDeletes}。</p>
 *
 * <p><b>交付语义</b>：AT_LEAST_ONCE——写入失败即抛 {@link IllegalStateException}，
 * 由 Flink 作业按重启策略重试（数据不静默丢弃）；精确一次（Doris 事务 label 去重）
 * 属后续段，见 {@code docs/realtime-chain-completion-plan.md} Phase B 验收。</p>
 *
 * @see Sink
 */
public final class DorisStreamLoadSink implements Sink<ChangeRecord>, Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(DorisStreamLoadSink.class);

    private final Options options;

    private DorisStreamLoadSink(Options options) {
        this.options = options;
    }

    /**
     * 创建 Builder。
     *
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** 供测试/诊断读取的不可变配置。 */
    public Options getOptions() {
        return options;
    }

    @Override
    public SinkWriter<ChangeRecord> createWriter(InitContext context) {
        log.info("Doris Stream Load Sink 初始化: {}:{}/api/{}/{}/_stream_load, batchSize={}",
                options.host(), options.port(), options.database(), options.table(), options.batchSize());
        return new DorisStreamLoadWriter(options);
    }

    /** 不可变配置（可序列化，随作业下发到 TM）。 */
    public static final class Options implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String host;
        private final int port;
        private final String database;
        private final String table;
        private final String username;
        private final String password;
        private final int batchSize;
        private final long connectTimeoutMs;
        private final long readTimeoutMs;
        private final String labels;
        /** 主键列（删除语义必需：DELETE ON 条件与 body 只用主键键集）。 */
        private final List<String> primaryKeys;

        Options(String host, int port, String database, String table, String username, String password,
                int batchSize, long connectTimeoutMs, long readTimeoutMs, String labels,
                List<String> primaryKeys) {
            this.host = host;
            this.port = port;
            this.database = database;
            this.table = table;
            this.username = username;
            this.password = password;
            this.batchSize = batchSize;
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
            this.labels = labels;
            this.primaryKeys = primaryKeys == null ? List.of() : List.copyOf(primaryKeys);
        }

        /** FE 主机。 */
        public String host() {
            return host;
        }

        /** FE HTTP 端口（Stream Load 默认 8030）。 */
        public int port() {
            return port;
        }

        /** 目标库。 */
        public String database() {
            return database;
        }

        /** 目标表。 */
        public String table() {
            return table;
        }

        /** 用户名。 */
        public String username() {
            return username;
        }

        /** 密码。 */
        public String password() {
            return password;
        }

        /** 单次 Stream Load 的最大记录数。 */
        public int batchSize() {
            return batchSize;
        }

        /** 连接超时（毫秒）。 */
        public long connectTimeoutMs() {
            return connectTimeoutMs;
        }

        /** 读超时（毫秒）。 */
        public long readTimeoutMs() {
            return readTimeoutMs;
        }

        /** 可选 label 前缀（非空时以 前缀-时间戳 作为本次导入 label）。 */
        public String labels() {
            return labels;
        }

        /** 主键列（删除语义必需；空列表时 d 事件将 fail-fast 指路配置）。 */
        public List<String> primaryKeys() {
            return primaryKeys;
        }
    }

    /** Sink 构造器。 */
    public static final class Builder {
        private String host;
        private int port = 8030;
        private String database;
        private String table;
        private String username = "root";
        private String password = "";
        private int batchSize = 500;
        private long connectTimeoutMs = 10_000L;
        private long readTimeoutMs = 60_000L;
        private String labels = "";
        private List<String> primaryKeys = List.of();

        /** FE 主机（必需）。 */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /** FE HTTP 端口（默认 8030）。 */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /** 目标库（必需）。 */
        public Builder database(String database) {
            this.database = database;
            return this;
        }

        /** 目标表（必需）。 */
        public Builder table(String table) {
            this.table = table;
            return this;
        }

        /** 用户名（默认 root）。 */
        public Builder username(String username) {
            this.username = username;
            return this;
        }

        /** 密码（默认空）。 */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /** 批量大小（默认 500）。 */
        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        /** 连接超时毫秒（默认 10000）。 */
        public Builder connectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        /** 读超时毫秒（默认 60000）。 */
        public Builder readTimeoutMs(long readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
            return this;
        }

        /** 可选 label 前缀。 */
        public Builder labels(String labels) {
            this.labels = labels;
            return this;
        }

        /**
         * 主键列（逗号分隔；删除语义必需——DELETE ON 条件与 body 只用主键键集，
         * 因为 pgoutput 的 before 非主键列为 null/占位值）。未配置时 d 事件
         * fail-fast 抛出指路异常（不静默丢删、不拼错条件）。
         */
        public Builder primaryKeys(String commaSeparated) {
            if (commaSeparated == null || commaSeparated.isBlank()) {
                this.primaryKeys = List.of();
            } else {
                this.primaryKeys = java.util.Arrays.stream(commaSeparated.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
            }
            return this;
        }

        /**
         * 构建 Sink。
         *
         * @return DorisStreamLoadSink
         * @throws IllegalArgumentException 必填项缺失或数值非法
         */
        public DorisStreamLoadSink build() {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("Doris Stream Load 缺少 host");
            }
            if (database == null || database.isBlank()) {
                throw new IllegalArgumentException("Doris Stream Load 缺少 database");
            }
            if (table == null || table.isBlank()) {
                throw new IllegalArgumentException("Doris Stream Load 缺少 table");
            }
            if (batchSize <= 0) {
                throw new IllegalArgumentException("batchSize 必须为正数: " + batchSize);
            }
            return new DorisStreamLoadSink(new Options(host, port, database, table, username, password,
                    batchSize, connectTimeoutMs, readTimeoutMs,
                    labels == null ? "" : labels, primaryKeys));
        }
    }

    /** 写入器：内存缓冲 + 批量 Stream Load；失败抛异常交由作业重试。 */
    static final class DorisStreamLoadWriter implements SinkWriter<ChangeRecord> {

        private static final ObjectMapper JSON = new ObjectMapper();

        private final Options options;
        private final HttpClient http;
        private final List<String> buffer = new ArrayList<>();
        /** delete 行缓冲：[bodyJson, deleteCondition] 对（d 行走 MERGE + DELETE ON 条件，见 flushBatch）。 */
        private final List<String[]> deleteBuffer = new ArrayList<>();
        private long loadedRecords = 0;
        private long loadedBatches = 0;

        DorisStreamLoadWriter(Options options) {
            this.options = options;
            this.http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(options.connectTimeoutMs()))
                    // 307（FE→BE 重定向）由 streamLoad() 手动处理：JDK 自动跟随会按
                    // authority 剥离 Authorization，导致 BE 报 [NOT_AUTHORIZED]（端到端泳道实证）
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }

        @Override
        public void write(ChangeRecord value, Context context) {
            Objects.requireNonNull(value, "ChangeRecord 不能为 null");
            String op = value.getOp();
            if ("d".equalsIgnoreCase(op)) {
                Map<String, Object> before = value.getBefore() != null ? value.getBefore() : value.getAfter();
                Map<String, Object> keys = selectDeleteKeys(before, options.primaryKeys());
                deleteBuffer.add(new String[]{rowJson(keys), buildDeleteCondition(keys)});
            } else {
                buffer.add(toRowJson(value));
            }
            if (buffer.size() + deleteBuffer.size() >= options.batchSize()) {
                flushBatch();
            }
        }

        /**
         * 删除键集选择：**只用主键列**（端到端实证——pgoutput 的 DELETE before
         * 非主键列为 Java null，条件若带它们会被拼成字符串 'null' 撞类型转换
         * 过滤（too many filtered rows），且 src_ts 等占位列永远不命中）。
         * 主键必须显式配置（yaml sink.primaryKey / properties primary-keys）；
         * 未配置或 before 缺主键列即 fail-fast（不静默丢删、不拼错条件）。
         */
        static Map<String, Object> selectDeleteKeys(Map<String, Object> before, List<String> primaryKeys) {
            if (before == null || before.isEmpty()) {
                throw new IllegalStateException("delete 行缺少 before 数据（op=d），无法构造删除键——不静默丢删");
            }
            if (primaryKeys == null || primaryKeys.isEmpty()) {
                throw new IllegalStateException(
                        "Doris 删除语义需要主键配置：请在 YAML sink 段配置 primaryKey（如 primaryKey: id）"
                                + "或 properties.primary-keys；不静默丢删也不拼错条件");
            }
            java.util.LinkedHashMap<String, Object> keys = new java.util.LinkedHashMap<>();
            for (String key : primaryKeys) {
                if (!before.containsKey(key)) {
                    throw new IllegalStateException(
                            "delete before 缺主键列「" + key + "」——检查主键配置与源表 REPLICA IDENTITY");
                }
                Object v = before.get(key);
                if (v == null) {
                    throw new IllegalStateException("delete 主键列「" + key + "」值为 null——不静默丢删");
                }
                keys.put(key, v);
            }
            return keys;
        }

        /**
         * before 键集 → Doris DELETE ON 条件（AND 连接）。
         *
         * <p>pgoutput 的 DELETE 事件 before 只含主键列（REPLICA IDENTITY 语义），
         * 因此 before 全键即主键条件。值形态受限安全拼接：列名白名单
         * （[A-Za-z_][A-Za-z0-9_]*）、Number 直拼（NaN/Infinity 拒绝）、
         * Boolean 直拼、其余按字符串单引号包裹并转义；嵌套结构拒绝（fail-fast，
         * 宁可不删不删错）。</p>
         */
        static String buildDeleteCondition(Map<String, Object> keys) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Object> e : keys.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(" AND ");
                }
                sb.append(deletePredicate(e.getKey(), e.getValue()));
            }
            return sb.toString();
        }

        private static String deletePredicate(String column, Object value) {
            if (column == null || !column.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalStateException("delete 条件列名不合法: " + column);
            }
            if (value instanceof Number num) {
                double d = num.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new IllegalStateException("delete 条件数值非法（NaN/Infinity）: " + column);
                }
                return column + " = " + num;
            }
            if (value instanceof Boolean b) {
                return column + " = " + b;
            }
            if (value != null && (value instanceof Iterable || value instanceof Map)) {
                throw new IllegalStateException(
                        "delete 条件不支持嵌套结构值: " + column + " -> " + value.getClass());
            }
            return column + " = '" + String.valueOf(value).replace("'", "\\'") + "'";
        }

        /**
         * ChangeRecord → Doris 行 JSON（**打平业务列**，端到端泳道实证形态修正）。
         *
         * <p>此前直接序列化整个 {@link ChangeRecord}（{@code {op, before, after}} 包裹形态），
         * Doris 按列名匹配时收不到 {@code after} 内的业务列（嵌套不打平）——Phase B
         * 端到端验收抓出的真缺陷。打平语义：{@code c/r/u} 取 after、{@code d} 取 before，
         * 并把顶层业务键平铺为行对象。删除语义不走该行 JSON（4.x 普通导入会忽略
         * {@code __DORIS_DELETE_SIGN__} 并整行 upsert 成 NULL 版本——端到端实证），
         * 改由 {@code buildDeleteCondition} + MERGE/DELETE ON 请求承载。</p>
         *
         * @param value 变更记录
         * @return 单行 JSON 对象（顶层即列）
         */
        static String toRowJson(ChangeRecord value) {
            String op = value.getOp();
            boolean isDelete = "d".equalsIgnoreCase(op);
            Map<String, Object> src = isDelete
                    ? (value.getBefore() != null ? value.getBefore() : value.getAfter())
                    : (value.getAfter() != null ? value.getAfter() : value.getBefore());
            if (src == null) {
                throw new IllegalStateException(
                        "变更记录缺少 before/after 数据（op=" + op + "），无法构造 Doris 行");
            }
            return rowJson(new java.util.HashMap<>(src));
        }

        /** Map → 单行 JSON 对象。 */
        private static String rowJson(Map<String, Object> row) {
            try {
                return JSON.writeValueAsString(row);
            } catch (Exception e) {
                throw new IllegalStateException("ChangeRecord 行序列化失败: " + e.getMessage(), e);
            }
        }

        @Override
        public void flush(boolean endOfInput) {
            flushBatch();
        }

        @Override
        public void close() {
            flushBatch();
            log.info("Doris Stream Load Sink 关闭: 累计 {} 批 / {} 条", loadedBatches, loadedRecords);
        }

        /** 当前累计成功写入条数（测试/监控用）。 */
        long getLoadedRecords() {
            return loadedRecords;
        }

        /** delete 条件拼接长度上限（超限拆多请求，避免 header 过大被拒）。 */
        private static final int DELETE_CONDITION_LIMIT = 4000;

        private void flushBatch() {
            List<String> batch = new ArrayList<>(buffer);
            List<String[]> dels = new ArrayList<>(deleteBuffer);
            buffer.clear();
            deleteBuffer.clear();

            if (!batch.isEmpty()) {
                String body = "[" + String.join(",", batch) + "]";
                try {
                    streamLoad(body, batch.size(), null);
                    loadedRecords += batch.size();
                    loadedBatches++;
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "Doris Stream Load 失败（本批 " + batch.size() + " 条将随作业重试）: " + e.getMessage(), e);
                }
            }
            if (!dels.isEmpty()) {
                flushDeletes(dels);
            }
        }

        /**
         * 删除批：MERGE + DELETE ON 条件（4.x 正路——普通导入的 __DORIS_DELETE_SIGN__
         * 会被忽略并整行 upsert 成 NULL 版本，端到端实证）。条件按行 OR 连接、
         * 超长拆分多请求；body 为该组行（before 键集）逐行 JSON 数组。
         */
        private void flushDeletes(List<String[]> dels) {
            List<String[]> group = new ArrayList<>();
            int condLen = 0;
            for (String[] pair : dels) {
                int add = pair[1].length() + 4; // " OR " 或首项
                if (!group.isEmpty() && condLen + add > DELETE_CONDITION_LIMIT) {
                    sendDeleteGroup(group);
                    group = new ArrayList<>();
                    condLen = 0;
                }
                group.add(pair);
                condLen += add;
            }
            if (!group.isEmpty()) {
                sendDeleteGroup(group);
            }
        }

        private void sendDeleteGroup(List<String[]> group) {
            StringBuilder cond = new StringBuilder();
            StringBuilder body = new StringBuilder("[");
            for (String[] pair : group) {
                if (cond.length() > 0) {
                    cond.append(" OR ");
                }
                cond.append('(').append(pair[1]).append(')');
                if (body.length() > 1) {
                    body.append(',');
                }
                body.append(pair[0]);
            }
            body.append(']');
            try {
                streamLoad(body.toString(), group.size(), cond.toString());
                loadedRecords += group.size();
                loadedBatches++;
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Doris 删除批失败（本批 " + group.size() + " 条将随作业重试）: " + e.getMessage()
                                + " | delete=" + head(cond.length() > 300 ? cond.substring(0, 300) : cond.toString())
                                + " | body=" + head(body.length() > 300 ? body.substring(0, 300) : body.toString()), e);
            }
        }

        private void streamLoad(String body, int rows, String deleteCondition) throws IOException {
            URI uri = URI.create("http://" + options.host() + ":" + options.port()
                    + "/api/" + options.database() + "/" + options.table() + "/_stream_load");
            String auth = "Basic " + Base64.getEncoder().encodeToString(
                    (options.username() + ":" + options.password()).getBytes(StandardCharsets.UTF_8));

            HttpResponse<String> response = send(auth, uri, body, deleteCondition);
            if (response.statusCode() == 307 || response.statusCode() == 308) {
                // Doris 4.x FE 将 stream load 重定向到 BE（host:port 变化）；JDK HttpClient
                // 的自动跟随会按 authority 剥离 Authorization ⇒ BE 收无认证请求报
                // [NOT_AUTHORIZED]（端到端泳道实证）。故禁自动跟随、手动重发并显式带认证。
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    throw new IOException("Doris Stream Load 重定向（HTTP " + response.statusCode()
                            + "）缺少 Location 头");
                }
                response = send(auth, URI.create(location), body, deleteCondition);
            }

            JsonNode resp;
            try {
                resp = JSON.readTree(response.body());
            } catch (Exception e) {
                throw new IOException("Doris Stream Load 响应非 JSON（HTTP " + response.statusCode()
                        + "，body 首 120 字符: " + head(response.body()) + "）", e);
            }
            String status = resp.path("Status").asText("");
            if (!"Success".equalsIgnoreCase(status)) {
                throw new IOException("Doris Stream Load 返回非成功: HTTP " + response.statusCode()
                        + ", Status=" + status + ", Message=" + resp.path("Message").asText(""));
            }
            log.info("Doris Stream Load 成功: {} 条 → {}.{}（NumberLoadedRows={}）",
                    rows, options.database(), options.table(), resp.path("NumberLoadedRows").asInt(rows));
        }

        /** 构造并发送单次 stream load 请求（含认证/格式头/100-continue；供 307 手动重发复用）。 */
        private HttpResponse<String> send(String auth, URI uri, String body, String deleteCondition)
                throws IOException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(options.readTimeoutMs()))
                    .header("Authorization", auth)
                    .header("format", "json")
                    .header("strip_outer_array", "true")
                    // Doris 4.x FE 强制要求 100-continue（缺则直接判 FAILED「There is no
                    // 100-continue header」）；Expect 是 JDK 受限头不可 .header() 直设，
                    // 用内置 expectContinue(true) 走协商（端到端泳道实证）
                    .expectContinue(true)
                    .header("Content-Type", "application/json");
            if (deleteCondition != null) {
                // DELETE ON 子句仅 MERGE 类型支持（DELETE 类型直发报
                // 「Not support DELETE ON clause when merge type is not MERGE」——实测）
                builder.header("merge_type", "MERGE").header("delete", deleteCondition);
            }
            if (!options.labels().isEmpty()) {
                builder.header("label", options.labels() + "-" + System.currentTimeMillis());
            }
            HttpRequest request = builder.PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            try {
                return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Doris Stream Load 被中断: " + e.getMessage(), e);
            }
        }

        private static String head(String s) {
            if (s == null) {
                return "";
            }
            return s.length() > 120 ? s.substring(0, 120) : s;
        }
    }
}
