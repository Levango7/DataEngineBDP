package com.levango7.dataenginebdp.sqlgateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Doris 真实扫描字节查询客户端（骨架）。
 *
 * <p>Doris 的查询扫描字节（ScanBytes）在 FE 审计日志表
 * {@code __internal_schema.audit_log} 中按 QueryId 记录。
 * 本客户端预留：通过 Doris 集群内一次查询
 * {@code SELECT ScanBytes FROM __internal_schema.audit_log WHERE QueryId = :queryId}
 * 获取真实字节，供计量计费使用（est=false）。
 *
 * <p>⚠️ 前置条件：
 * <ul>
 *   <li>Doris 需开启审计日志（FE 配置 {@code enable_audit_log=true}）</li>
 *   <li>执行 SQL 的账号需有访问 audit_log 的权限</li>
 *   <li>需在真集群上验证查询语句与权限，本地无 Doris 时本类不会被调用</li>
 * </ul>
 *
 * <p>当前为骨架：{@link #fetchScanBytes(String)} 直接返回 null（回退估算），
 * 待真集群可用后在此实现 SQL 查询并接入 metering 链路。
 */
@Component
public class DorisScanStatsClient {

    private static final Logger log = LoggerFactory.getLogger(DorisScanStatsClient.class);

    private final RestClient restClient;
    private final String dorisUrl;
    private final boolean enabled;

    public DorisScanStatsClient(@Value("${app.backend.doris.url:http://doris-fe-service:9030}") String dorisUrl,
                                @Value("${app.backend.doris.scan-stats-enabled:false}") boolean enabled) {
        this.dorisUrl = dorisUrl;
        this.enabled = enabled;
        this.restClient = RestClient.builder().baseUrl(dorisUrl).build();
        log.info("DorisScanStatsClient 初始化: url={}, enabled={}", dorisUrl, enabled);
    }

    /**
     * 按 Doris 原生 QueryId 查真实扫描字节。
     *
     * @param dorisQueryId Doris 返回的 QueryId
     * @return 扫描字节数；不可用/未启用返回 null
     */
    public Long fetchScanBytes(String dorisQueryId) {
        if (!enabled || dorisQueryId == null || dorisQueryId.isBlank()) {
            return null;
        }
        log.debug("TODO: 查询 Doris 审计日志 ScanBytes queryId={}（需 Doris 开启 enable_audit_log 且账号有权限）",
                dorisQueryId);
        // TODO(real-cluster): 执行
        //   SELECT ScanBytes FROM __internal_schema.audit_log WHERE QueryId = '{queryId}'
        //   解析结果并返回；超时/无权限时返回 null（上层回退估算）
        return null;
    }
}