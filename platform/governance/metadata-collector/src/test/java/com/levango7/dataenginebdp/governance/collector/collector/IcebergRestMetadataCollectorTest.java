package com.levango7.dataenginebdp.governance.collector.collector;

import com.levango7.dataenginebdp.governance.collector.model.CollectionResult;
import com.levango7.dataenginebdp.governance.collector.model.MetadataSource;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IcebergRestMetadataCollector 集成测试（JDK HttpServer mock REST Catalog）。
 *
 * <p>验证真实 HTTP 采集链路：命名空间 → 表 → schema → 分区键 → 快照数。
 * Catalog 不可达时 collect 返回 success=false（不抛异常）。</p>
 */
class IcebergRestMetadataCollectorTest {

    private HttpServer server;
    private IcebergRestMetadataCollector collector;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // mock Iceberg REST Catalog
        server.createContext("/v1/catalog/namespaces", exchange -> {
            String body = "{\"namespaces\":[[\"ods\"],[\"dws\"]]}";
            respond(exchange, body);
        });
        server.createContext("/v1/catalog/namespaces/ods/tables", exchange -> {
            String body = "{\"identifiers\":[{\"name\":\"orders\"},{\"name\":\"users\"}]}";
            respond(exchange, body);
        });
        server.createContext("/v1/catalog/namespaces/dws/tables", exchange -> {
            String body = "{\"identifiers\":[{\"name\":\"order_daily\"}]}";
            respond(exchange, body);
        });
        server.createContext("/v1/catalog/namespaces/ods/tables/orders", exchange -> {
            String body = "{\"schema\":{\"fields\":["
                    + "{\"name\":\"order_id\",\"type\":\"long\"},"
                    + "{\"name\":\"amount\",\"type\":\"double\"}]},"
                    + "\"partition-spec\":{\"fields\":[{\"name\":\"order_id\"}]},"
                    + "\"snapshots\":[{},{}]}";
            respond(exchange, body);
        });
        // 其他表简化返回
        server.createContext("/v1/catalog/namespaces/ods/tables/users", exchange -> {
            String body = "{\"schema\":{\"fields\":[{\"name\":\"user_id\",\"type\":\"long\"}]}}";
            respond(exchange, body);
        });
        server.createContext("/v1/catalog/namespaces/dws/tables/order_daily", exchange -> {
            String body = "{\"schema\":{\"fields\":[{\"name\":\"dt\",\"type\":\"date\"}]}}";
            respond(exchange, body);
        });
        server.start();
        collector = new IcebergRestMetadataCollector();
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void collect_fetchesNamespacesTablesSchema() {
        MetadataSource source = new MetadataSource();
        source.setId(1L);
        source.setName("iceberg-prod");
        source.setType(MetadataSource.TYPE_ICEBERG);
        source.setUrl("http://127.0.0.1:" + server.getAddress().getPort());

        CollectionResult result = collector.collect(source);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTables()).hasSize(3); // ods.orders/users + dws.order_daily

        // 校验 orders 表 schema
        var orders = result.getTables().stream()
                .filter(t -> "orders".equals(t.getTableName()))
                .findFirst().orElseThrow();
        assertThat(orders.getDatabaseName()).isEqualTo("ods");
        assertThat(orders.getTableType()).isEqualTo("ICEBERG_TABLE");
        assertThat(orders.getColumns()).hasSize(2);
        assertThat(orders.getColumns().get(0).getName()).isEqualTo("order_id");
        assertThat(orders.getColumns().get(0).getType()).isEqualTo("long");
        assertThat(orders.getPartitionKeys()).contains("order_id");
        assertThat(orders.getProperties()).containsEntry("snapshotCount", "2");
    }

    @Test
    void collect_catalogUnreachable_returnsSuccessFalse() {
        MetadataSource source = new MetadataSource();
        source.setId(2L);
        source.setName("iceberg-down");
        source.setType(MetadataSource.TYPE_ICEBERG);
        source.setUrl("http://127.0.0.1:1"); // 不可达

        CollectionResult result = collector.collect(source);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isNotBlank();
    }

    @Test
    void testConnection_validCatalog_returnsTrue() {
        MetadataSource source = new MetadataSource();
        source.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        assertThat(collector.testConnection(source)).isTrue();
    }
}
