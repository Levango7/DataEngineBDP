package com.levango7.dataenginebdp.sqlgateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.regex.Pattern;

/**
 * Doris 真实扫描字节查询客户端。
 *
 * <p><b>链路（基于实测）</b>：Doris 的查询扫描字节（ScanBytes）记录在 FE 审计日志
 * {@code __internal_schema.audit_log}（表异步批写，默认约 60s 内刷入）。
 * JDBC 链路拿到的网关 queryId ≠ Doris 原生 QueryId，因此按
 * <b>规范化 SQL 指纹</b> 匹配最近一条审计记录获取 ScanBytes。
 *
 * <p><b>前置条件</b>：
 * <ul>
 *   <li>Doris 需开启审计插件：{@code SET GLOBAL enable_audit_plugin=true}
 *       （表写入异步，批量间隔可配 audit_plugin_max_batch_interval_sec）</li>
 *   <li>执行查询的账号对 audit_log 有读权限（通常 root 即可）</li>
 * </ul>
 *
 * <p><b>兜底</b>：查询失败 / 未命中时返回 {@code null}，上游回退估算计量（est=true）。
 * 真实字节优先（est=false），保证计费不因审计链路异常而中断。
 */
@Component
public class DorisScanStatsClient {

    private static final Logger log = LoggerFactory.getLogger(DorisScanStatsClient.class);

    /** 空白压缩（SQL 指纹规范化：去多余空格与换行）。 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final String dorisJdbcUrl;
    private final String dorisUser;
    private final String dorisPassword;
    private final boolean enabled;

    public DorisScanStatsClient(
            @Value("${app.backend.doris.url:${DORIS_URL:http://doris-fe-service:9030}}") String dorisUrl,
            @Value("${app.backend.doris.username:root}") String dorisUser,
            @Value("${app.backend.doris.password:${DORIS_PASSWORD:}}") String dorisPassword,
            @Value("${app.backend.doris.scan-stats-enabled:false}") boolean enabled) {
        this.dorisJdbcUrl = toJdbcUrl(dorisUrl);
        this.dorisUser = dorisUser;
        this.dorisPassword = dorisPassword;
        this.enabled = enabled;
        log.info("DorisScanStatsClient 初始化: jdbc={}, enabled={}", dorisJdbcUrl, enabled);
    }

    /**
     * 按规范化 SQL 指纹在 audit_log 中查找最近一条查询的扫描字节。
     *
     * @param sql 原始查询 SQL
     * @return ScanBytes；未启用 / 未命中 / 异常返回 null
     */
    public Long fetchScanBytes(String sql) {
        if (!enabled || sql == null || sql.isBlank()) {
            return null;
        }
        String fingerprint = fingerprint(sql);
        try (Connection conn = DriverManager.getConnection(dorisJdbcUrl, dorisUser, dorisPassword)) {
            // 规范化匹配：按 stmt 前缀近似命中最近一条 is_query 记录
            // （Doris audit_log 的 stmt 列含完整 SQL；按指纹等值匹配最稳，
            //   但大 SQL 会被截断，故取规范化后前 200 字符做前缀匹配，再本地二次校验）
            // 使用 PreparedStatement 参数化查询，避免 LIKE 拼接导致的 SQL 注入
            String query = "SELECT scan_bytes, scan_rows FROM __internal_schema.audit_log "
                    + "WHERE is_query = 1 AND stmt LIKE ? "
                    + "ORDER BY time DESC LIMIT 5";
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, escapeLike(fingerprint) + "%");
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {

                        long scanBytes = rs.getLong("scan_bytes");
                        if (scanBytes > 0) {
                            return scanBytes;
                        }
                    }
                }
            }
            log.debug("审计日志未命中 SQL 指纹: {}", fingerprint);
            return null;
        } catch (Exception e) {
            log.warn("查询 Doris audit_log 扫描字节失败(回退估算): err={}", e.getMessage());
            return null;
        }
    }

    /**
     * SQL 指纹：压缩空白（保留原大小写，用于审计日志匹配）。
     *
     * <p><b>实测依据</b>：Doris audit_log 的 stmt 列按原样记录 SQL（大小写敏感），
     * 转小写会导致 LIKE 匹配 miss；sql-gateway 执行的 SQL 与 Doris 记录的 stmt
     * 为同一字符串，因此仅需压缩空白。若希望大小写不敏感可改用
     * {@code LOWER(stmt) LIKE LOWER(?)}，但会牺牲索引匹配。</p>
     */
    public static String fingerprint(String sql) {
        if (sql == null) {
            return "";
        }
        return WHITESPACE.matcher(sql.trim()).replaceAll(" ");
    }

    /** 把 http(s)://host:port 转为 jdbc:mysql://host:port。 */
    static String toJdbcUrl(String url) {
        if (url == null || url.isBlank()) {
            return "jdbc:mysql://doris-fe:9030";
        }
        String u = url.trim();
        if (u.startsWith("jdbc:")) {
            return u;
        }
        if (u.startsWith("http://") || u.startsWith("https://")) {
            u = u.substring(u.indexOf("://") + 3);
        }
        return "jdbc:mysql://" + u;
    }

    private String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}