package com.levango7.dataenginebdp.flinkcdc;

import com.levango7.dataenginebdp.flinkcdc.sink.DorisStreamLoadSink;
import com.levango7.dataenginebdp.flinkcdc.sink.IcebergSinkConnector;
import com.levango7.dataenginebdp.flinkcdc.sink.SinkConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CDC→入湖 Sink 接线映射单测（Phase A1/B1）：SinkConfig → 连接器/Sink 的字段映射与必填校验。
 */
class CdcFrameworkSinkWiringTest {

    @Test
    @DisplayName("Iceberg 映射：必填齐备时构建成功（REST catalog + UPSERT + 主键）")
    void icebergHappyPath() {
        SinkConfig config = new SinkConfig();
        config.setName("ice-main");
        config.setType(SinkConfig.SinkType.ICEBERG);
        config.property("catalog-uri", "http://iceberg-rest:8181");
        config.property("warehouse", "s3://shuqing-warehouse/");
        config.property("database", "dw");
        config.property("table", "orders");
        config.property("primary-keys", "id");

        IcebergSinkConnector connector = CdcFramework.buildIcebergConnector(config);
        assertNotNull(connector);
        assertNotNull(connector.createIcebergProperties());
        assertTrue(connector.createIcebergProperties().containsKey("catalog-name"));
    }

    @Test
    @DisplayName("Iceberg 映射：缺 warehouse 时报错并点名（fail-fast，不静默丢弃）")
    void icebergMissingWarehouseFails() {
        SinkConfig config = new SinkConfig();
        config.setName("ice-bad");
        config.setType(SinkConfig.SinkType.ICEBERG);
        config.property("database", "dw");
        config.property("table", "orders");
        config.property("primary-keys", "id");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> CdcFramework.buildIcebergConnector(config));
        assertTrue(ex.getMessage().contains("warehouse"));
    }

    @Test
    @DisplayName("Doris 映射：host/port/库表/批量逐项落位；table 缺省回落 topic")
    void dorisMapping() {
        SinkConfig config = new SinkConfig();
        config.setName("doris-main");
        config.setType(SinkConfig.SinkType.DORIS);
        config.setHost("10.0.0.9");
        config.setPort(8030);
        config.setUsername("root");
        config.setPassword("secret");
        config.property("database", "report");
        config.property("table", "mv_sales");
        config.property("batch-size", "10");

        DorisStreamLoadSink sink = CdcFramework.buildDorisSink(config);
        DorisStreamLoadSink.Options options = sink.getOptions();
        assertEquals("10.0.0.9", options.host());
        assertEquals(8030, options.port());
        assertEquals("report", options.database());
        assertEquals("mv_sales", options.table());
        assertEquals("root", options.username());
        assertEquals(10, options.batchSize());

        SinkConfig fallback = new SinkConfig();
        fallback.setHost("10.0.0.9");
        fallback.setPort(8030);
        fallback.setTopic("tbl_by_topic");
        fallback.property("database", "report");
        assertEquals("tbl_by_topic", CdcFramework.buildDorisSink(fallback).getOptions().table());
    }

    @Test
    @DisplayName("Doris 映射：缺 database 时构建即失败")
    void dorisMissingDatabaseFails() {
        SinkConfig config = new SinkConfig();
        config.setHost("10.0.0.9");
        config.setPort(8030);
        config.property("table", "mv_sales");
        assertThrows(IllegalArgumentException.class, () -> CdcFramework.buildDorisSink(config));
    }
}
