package com.levango7.dataenginebdp.sqlgateway.metering;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QueryMeter 与 MeteringCollector 基础行为测试（不发起真实 HTTP）。
 */
class QueryMeterTest {

    @Test
    void toPayload_mapsAllFields() {
        QueryMeter meter = new QueryMeter(
                "tenant_a", "analysis", "trino", "hash-1",
                123456L, true, 250L, "query-uuid-1");

        Map<String, Object> payload = meter.toPayload();

        assertThat(payload.get("tenantId")).isEqualTo("tenant_a");
        assertThat(payload.get("engine")).isEqualTo("trino");
        assertThat(payload.get("bytesScanned")).isEqualTo(123456L);
        assertThat(payload.get("estimated")).isEqualTo(true);
        assertThat(payload.get("durationMs")).isEqualTo(250L);
        assertThat(payload.get("clientRequestId")).isEqualTo("query-uuid-1");
    }

    @Test
    void constructor_withNullNamespace() {
        QueryMeter meter = new QueryMeter(
                "tenant_a", null, "doris", null,
                10L, false, null, "query-uuid-2");

        Map<String, Object> payload = meter.toPayload();
        // 空值字段 payload 用空串兜底，避免 JSON null 上报
        assertThat(payload.get("namespace")).isEqualTo("");
        assertThat(payload.get("sqlHash")).isEqualTo("");
    }

    @Test
    void collector_bufferOverflowDoesNotThrow() {
        // 有界缓冲溢出时丢弃新计量而非抛异常（不得影响查询主链路）
        MeteringCollector collector = new MeteringCollector("http://localhost:18090");
        // 最大 1024，往 buffer 写 1030 条（前 1024 命中，溢出丢弃不抛）
        for (int i = 0; i < 1030; i++) {
            collector.submit(new QueryMeter("t", null, "trino", null,
                    1L, true, null, "q-" + i));
        }
        assertThat(collector.pendingCount()).isLessThanOrEqualTo(1024);
    }
}