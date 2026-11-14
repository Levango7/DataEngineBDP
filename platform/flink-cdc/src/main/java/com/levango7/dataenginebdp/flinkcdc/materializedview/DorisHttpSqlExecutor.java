package com.levango7.dataenginebdp.flinkcdc.materializedview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.flinkcdc.materializedview.config.MaterializedViewConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Function;

/**
 * Doris SQL 执行器（物化刷新 SQL 的落地通道，实时链路补齐专项 Phase C1 配套）。
 *
 * <p>经 Doris FE HTTP 接口执行 SQL：{@code POST /api/query/{db}}，Basic 认证。
 * 实现 {@link Function}{@code <String, Boolean>}——即 {@link
 * com.levango7.dataenginebdp.flinkcdc.materializedview.refresh.ViewRefresher}
 * 约定的 SQL 执行器契约：<b>成功返回 true、失败返回 false（不抛异常）</b>，
 * 失败细节记 WARN 日志（刷新结果会如实呈现失败）。</p>
 *
 * <p>FE 地址取 {@code materializedview.doris.fe-hosts} 的**首个**主机
 * （逗号分隔列表的第一项）；多 FE 负载均衡/故障转移属后续段。</p>
 */
public final class DorisHttpSqlExecutor implements Function<String, Boolean> {

    private static final Logger log = LoggerFactory.getLogger(DorisHttpSqlExecutor.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String feHost;
    private final String database;
    private final String username;
    private final String password;
    private final HttpClient http;
    private final long readTimeoutMs;

    /**
     * 从物化视图配置构建（供 Spring 宿主装配）。
     *
     * @param config 物化视图全局配置
     */
    public DorisHttpSqlExecutor(MaterializedViewConfig config) {
        this(config.getDoris().getFeHosts(),
                config.getDoris().getUsername(),
                config.getDoris().getPassword(),
                config.getDoris().getDatabase(),
                config.getDoris().getConnectTimeoutMs(),
                config.getDoris().getReadTimeoutMs());
    }

    /**
     * 显式参数构建（供测试/嵌入使用）。
     *
     * @param feHosts          逗号分隔的 FE 主机列表（取首个）
     * @param username         用户名
     * @param password         密码
     * @param database         默认库
     * @param connectTimeoutMs 连接超时（毫秒）
     * @param readTimeoutMs    读超时（毫秒）
     */
    public DorisHttpSqlExecutor(String feHosts, String username, String password, String database,
                                long connectTimeoutMs, long readTimeoutMs) {
        this.feHost = firstHost(feHosts);
        this.database = database == null ? "" : database;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.readTimeoutMs = readTimeoutMs;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }

    @Override
    public Boolean apply(String sql) {
        if (sql == null || sql.isBlank()) {
            log.warn("Doris SQL 执行器收到空 SQL，跳过");
            return false;
        }
        try {
            URI uri = URI.create("http://" + feHost + "/api/query/" + database);
            String auth = "Basic " + Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .header("Authorization", auth)
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode resp = JSON.readTree(response.body());
            int code = resp.path("code").asInt(-1);
            String msg = resp.path("msg").asText("");
            boolean ok = code == 0 || "success".equalsIgnoreCase(msg) || "ok".equalsIgnoreCase(msg);
            if (!ok) {
                log.warn("Doris SQL 执行失败: HTTP {}, code={}, msg={}（SQL 首 80 字符: {}）",
                        response.statusCode(), code, msg, head(sql));
            }
            return ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Doris SQL 执行被中断（SQL 首 80 字符: {}）", head(sql));
            return false;
        } catch (Exception e) {
            log.warn("Doris SQL 执行异常: {}（SQL 首 80 字符: {}）", e.getMessage(), head(sql));
            return false;
        }
    }

    /** 取逗号分隔主机列表的首个（去空白）。 */
    static String firstHost(String feHosts) {
        Objects.requireNonNull(feHosts, "feHosts 不能为 null");
        for (String part : feHosts.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        throw new IllegalArgumentException("feHosts 为空: " + feHosts);
    }

    private static String head(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 80 ? s.substring(0, 80) : s;
    }
}
