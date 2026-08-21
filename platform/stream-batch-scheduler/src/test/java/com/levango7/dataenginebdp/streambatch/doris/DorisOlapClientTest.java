package com.levango7.dataenginebdp.streambatch.doris;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.streambatch.router.ViewRouterConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DorisOlapClient 单元测试。
 *
 * <p>使用 OkHttp MockWebServer 模拟 Doris FE HTTP API，验证：
 * <ul>
 *   <li>查询成功时正确解析 columnNames 与 rows</li>
 *   <li>查询失败（HTTP 500）抛 DorisOlapException</li>
 *   <li>Doris 返回错误码（code != 0）抛 DorisOlapException</li>
 *   <li>物化视图刷新成功 / 失败</li>
 * </ul>
 */
class DorisOlapClientTest {

    private MockWebServer mockServer;
    private ViewRouterConfig config;
    private DorisOlapClient client;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
        String baseUrl = mockServer.url("/").toString().replaceAll("/$", "");
        config = new ViewRouterConfig();
        config.setDorisFeRest(baseUrl);
        config.setDorisUser("root");
        config.setDorisPassword("");
        client = new DorisOlapClient(config);
    }

    @AfterEach
    void tearDown() throws Exception {
        mockServer.shutdown();
    }

    @Test
    void query_success_parsesColumnsAndRows() throws Exception {
        // Doris FE /api/<db> 返回格式
        String resp = "{"
                + "\"code\":\"0\","
                + "\"msg\":\"success\","
                + "\"data\":{"
                + "\"column_names\":[\"order_date\",\"order_cnt\",\"total_amount\"],"
                + "\"rows\":[[\"2026-08-01\",\"128\",\"256000.50\"],[\"2026-08-02\",\"256\",\"512001.00\"]]"
                + "}"
                + "}";
        mockServer.enqueue(new MockResponse().setBody(resp).setResponseCode(200));

        DorisQueryResult result = client.query("dwd", "SELECT * FROM dws_user_order_1d");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getDatabase()).isEqualTo("dwd");
        assertThat(result.getColumnNames()).containsExactly("order_date", "order_cnt", "total_amount");
        assertThat(result.getRowCount()).isEqualTo(2);
        assertThat(result.getRows().get(0)).containsEntry("order_date", "2026-08-01");
        assertThat(result.getRows().get(0)).containsEntry("order_cnt", "128");
        assertThat(result.getElapsedMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void query_httpFailure_throwsException() {
        mockServer.enqueue(new MockResponse().setBody("internal error").setResponseCode(500));

        assertThatThrownBy(() -> client.query("dwd", "SELECT 1"))
                .isInstanceOf(DorisOlapException.class)
                .hasMessageContaining("Doris 查询失败")
                .hasMessageContaining("httpCode=500");
    }

    @Test
    void query_dorisErrorCode_throwsException() {
        String resp = "{\"code\":\"1\",\"msg\":\"table not found\"}";
        mockServer.enqueue(new MockResponse().setBody(resp).setResponseCode(200));

        assertThatThrownBy(() -> client.query("dwd", "SELECT * FROM unknown"))
                .isInstanceOf(DorisOlapException.class)
                .hasMessageContaining("Doris 返回错误")
                .hasMessageContaining("table not found");
    }

    @Test
    void query_emptyResult_returnsZeroRows() throws Exception {
        String resp = "{\"code\":\"0\",\"msg\":\"success\","
                + "\"data\":{\"column_names\":[\"c1\"],\"rows\":[]}}";
        mockServer.enqueue(new MockResponse().setBody(resp).setResponseCode(200));

        DorisQueryResult result = client.query("dwd", "SELECT 1 AS c1 WHERE 1=0");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(0);
        assertThat(result.getColumnNames()).containsExactly("c1");
    }

    @Test
    void refreshMaterializedView_success_returnsTrue() throws Exception {
        mockServer.enqueue(new MockResponse().setBody("{\"code\":\"0\"}").setResponseCode(200));
        boolean ok = client.refreshMaterializedView("dwd", "mv_dws_user_order_1d");
        assertThat(ok).isTrue();
    }

    @Test
    void refreshMaterializedView_failure_throwsException() {
        mockServer.enqueue(new MockResponse().setBody("forbidden").setResponseCode(403));
        assertThatThrownBy(() -> client.refreshMaterializedView("dwd", "mv_unknown"))
                .isInstanceOf(DorisOlapException.class)
                .hasMessageContaining("物化视图刷新失败")
                .hasMessageContaining("httpCode=403");
    }

    @Test
    void createIcebergExternalCatalog_success() throws Exception {
        String resp = "{\"code\":\"0\",\"msg\":\"success\","
                + "\"data\":{\"column_names\":[],\"rows\":[]}}";
        mockServer.enqueue(new MockResponse().setBody(resp).setResponseCode(200));
        boolean ok = client.createIcebergExternalCatalog(
                "iceberg_trade", "s3://warehouse/iceberg", "hive");
        assertThat(ok).isTrue();
    }

    @Test
    void queryTableStats_returnsRowCount() throws Exception {
        String resp = "{\"code\":\"0\",\"msg\":\"success\","
                + "\"data\":{\"column_names\":[\"row_count\"],\"rows\":[[\"1024\"]]}}";
        mockServer.enqueue(new MockResponse().setBody(resp).setResponseCode(200));
        var stats = client.queryTableStats("dwd", "dws_user_order_1d");
        assertThat(stats).containsEntry("rowCount", "1024");
        assertThat(stats).containsKeys("database", "table", "elapsedMs");
    }
}