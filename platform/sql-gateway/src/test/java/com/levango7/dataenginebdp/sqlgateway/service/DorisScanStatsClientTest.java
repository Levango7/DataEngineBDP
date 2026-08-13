package com.levango7.dataenginebdp.sqlgateway.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DorisScanStatsClient 纯逻辑单元测试（不连真实 Doris）。
 */
class DorisScanStatsClientTest {

    @Test
    void fingerprint_normalizesWhitespaceKeepsCase() {
        // Doris audit_log stmt 列大小写敏感,指纹仅压缩空白,保留原大小写
        String result = DorisScanStatsClient.fingerprint("  SELECT   a , b\n  FROM  tbl  WHERE  x=1  ");
        assertThat(result).isEqualTo("SELECT a , b FROM tbl WHERE x=1");
    }

    @Test
    void fingerprint_handlesNullAndBlank() {
        assertThat(DorisScanStatsClient.fingerprint(null)).isEmpty();
        assertThat(DorisScanStatsClient.fingerprint("   ")).isEmpty();
    }

    @Test
    void toJdbcUrl_convertsHttp() {
        assertThat(DorisScanStatsClient.toJdbcUrl("http://doris-fe-service:9030"))
                .isEqualTo("jdbc:mysql://doris-fe-service:9030");
        assertThat(DorisScanStatsClient.toJdbcUrl("https://doris2:9030"))
                .isEqualTo("jdbc:mysql://doris2:9030");
    }

    @Test
    void toJdbcUrl_keepsJdbcPrefix() {
        assertThat(DorisScanStatsClient.toJdbcUrl("jdbc:mysql://localhost:9030"))
                .isEqualTo("jdbc:mysql://localhost:9030");
    }

    @Test
    void toJdbcUrl_defaultsFallback() {
        assertThat(DorisScanStatsClient.toJdbcUrl(null))
                .isEqualTo("jdbc:mysql://doris-fe:9030");
        assertThat(DorisScanStatsClient.toJdbcUrl(""))
                .isEqualTo("jdbc:mysql://doris-fe:9030");
    }

    @Test
    void disabledClientReturnsNullWithoutNetwork() {
        // enabled=false 时 fetchScanBytes 应直接返回 null，不触发 JDBC 连接
        DorisScanStatsClient client = new DorisScanStatsClient("http://doris:9030", "root", false);
        assertThat(client.fetchScanBytes("SELECT 1")).isNull();
    }
}