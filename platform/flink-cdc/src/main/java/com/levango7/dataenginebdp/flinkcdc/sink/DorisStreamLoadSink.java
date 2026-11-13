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
import java.util.Objects;

/**
 * Doris Stream Load Sink（CDC→Doris 入湖，实时链路补齐专项 Phase B1）。
 *
 * <p>通过 Doris FE 的 Stream Load HTTP 接口按批写入：{@code PUT /api/{db}/{table}/_stream_load}，
 * Basic 认证 + {@code format: json} + {@code strip_outer_array: true}（请求体为 JSON 数组）。
 * 依赖面仅 JDK {@link HttpClient} 与 Jackson，不引入 Doris 官方 Flink Connector（重依赖）。</p>
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

        Options(String host, int port, String database, String table, String username, String password,
                int batchSize, long connectTimeoutMs, long readTimeoutMs, String labels) {
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
                    labels == null ? "" : labels));
        }
    }

    /** 写入器：内存缓冲 + 批量 Stream Load；失败抛异常交由作业重试。 */
    static final class DorisStreamLoadWriter implements SinkWriter<ChangeRecord> {

        private static final ObjectMapper JSON = new ObjectMapper();

        private final Options options;
        private final HttpClient http;
        private final List<String> buffer = new ArrayList<>();
        private long loadedRecords = 0;
        private long loadedBatches = 0;

        DorisStreamLoadWriter(Options options) {
            this.options = options;
            this.http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(options.connectTimeoutMs()))
                    .build();
        }

        @Override
        public void write(ChangeRecord value, Context context) {
            Objects.requireNonNull(value, "ChangeRecord 不能为 null");
            try {
                buffer.add(JSON.writeValueAsString(value));
            } catch (Exception e) {
                throw new IllegalStateException("ChangeRecord 序列化失败: " + e.getMessage(), e);
            }
            if (buffer.size() >= options.batchSize()) {
                flushBatch();
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

        private void flushBatch() {
            if (buffer.isEmpty()) {
                return;
            }
            List<String> batch = new ArrayList<>(buffer);
            buffer.clear();
            String body = "[" + String.join(",", batch) + "]";
            try {
                streamLoad(body, batch.size());
                loadedRecords += batch.size();
                loadedBatches++;
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Doris Stream Load 失败（本批 " + batch.size() + " 条将随作业重试）: " + e.getMessage(), e);
            }
        }

        private void streamLoad(String body, int rows) throws IOException {
            URI uri = URI.create("http://" + options.host() + ":" + options.port()
                    + "/api/" + options.database() + "/" + options.table() + "/_stream_load");
            String auth = "Basic " + Base64.getEncoder().encodeToString(
                    (options.username() + ":" + options.password()).getBytes(StandardCharsets.UTF_8));
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(options.readTimeoutMs()))
                    .header("Authorization", auth)
                    .header("format", "json")
                    .header("strip_outer_array", "true")
                    .header("Content-Type", "application/json");
            if (!options.labels().isEmpty()) {
                builder.header("label", options.labels() + "-" + System.currentTimeMillis());
            }
            HttpRequest request = builder.PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();

            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Doris Stream Load 被中断: " + e.getMessage(), e);
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

        private static String head(String s) {
            if (s == null) {
                return "";
            }
            return s.length() > 120 ? s.substring(0, 120) : s;
        }
    }
}
