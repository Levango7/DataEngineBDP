package com.levango7.dataenginebdp.flinkcdc.sink;

import com.levango7.dataenginebdp.flinkcdc.model.ChangeRecord;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.table.data.RowData;
import org.apache.flink.types.RowKind;
import org.apache.iceberg.Schema;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChangeRecord → RowData mapper 单测（A1 终段转换面）：真实 iceberg Schema 编译期构造，
 * 断言 RowKind 映射（c/u/d）与按列类型窄转换（LONG/STRING/DOUBLE/BOOLEAN/null）。
 */
class ChangeRecordRowDataMapperTest {

    private static final Schema SCHEMA = new Schema(
            Types.NestedField.required(1, "id", Types.LongType.get()),
            Types.NestedField.optional(2, "name", Types.StringType.get()),
            Types.NestedField.optional(3, "amount", Types.DoubleType.get()),
            Types.NestedField.optional(4, "active", Types.BooleanType.get()));

    private ChangeRecordRowDataMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ChangeRecordRowDataMapper(SCHEMA, true);
        mapper.open(new Configuration());
    }

    private ChangeRecord cdcRecord(String op, Map<String, Object> after, Map<String, Object> before) {
        ChangeRecord record = new ChangeRecord();
        record.setOp(op);
        if (after != null) {
            record.setAfter(after);
        }
        if (before != null) {
            record.setBefore(before);
        }
        return record;
    }

    @Test
    @DisplayName("timestamptz 带时区指示符与变长微秒：withZone 字段转 UTC epoch millis，无 zone 字段转墙上时间")
    void timestampWithZoneSuffixAndMicros() {
        Schema tsSchema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.optional(2, "ts_ltz", Types.TimestampType.withZone()),
                Types.NestedField.optional(3, "ts_plain", Types.TimestampType.withoutZone()));
        ChangeRecordRowDataMapper tsMapper = new ChangeRecordRowDataMapper(tsSchema, true);
        tsMapper.open(new Configuration());
        // pgoutput 实际产出形态（端到端泳道实证）：微秒 + Z 尾
        String raw = "2026-11-14T23:35:48.719472Z";
        RowData row = tsMapper.map(cdcRecord("c", Map.<String, Object>of(
                "id", 1L, "ts_ltz", raw, "ts_plain", raw), null));
        long expectedUtcMs = java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli();
        assertEquals(expectedUtcMs, row.getTimestamp(1, 6).getMillisecond(),
                "withZone 字段 = UTC epoch millis");
        assertEquals(java.time.LocalDateTime.parse("2026-11-14T23:35:48.719472"),
                row.getTimestamp(2, 6).toLocalDateTime(),
                "无 zone 字段 = 墙上时间（UTC 视角）");
    }

    @Test
    @DisplayName("op=c → INSERT 行：四列按类型窄转换（LONG/STRING/DOUBLE/BOOLEAN）")
    void insertRowConversion() {
        RowData row = mapper.map(cdcRecord("c",
                Map.<String, Object>of("id", 1L, "name", "alpha", "amount", 9.5, "active", true), null));
        assertEquals(RowKind.INSERT, row.getRowKind());
        assertEquals(1L, row.getLong(0));
        assertEquals("alpha", row.getString(1).toString());
        assertEquals(9.5, row.getDouble(2), 1e-9);
        assertTrue(row.getBoolean(3));
    }

    @Test
    @DisplayName("op=u → UPDATE_AFTER；op=d → DELETE 且取 before 快照")
    void rowKinds() {
        RowData update = mapper.map(cdcRecord("u",
                Map.<String, Object>of("id", 2L, "name", "beta"), null));
        assertEquals(RowKind.UPDATE_AFTER, update.getRowKind());
        assertEquals(2L, update.getLong(0));

        RowData delete = mapper.map(cdcRecord("d", null, Map.<String, Object>of("id", 2L, "name", "beta")));
        assertEquals(RowKind.DELETE, delete.getRowKind());
        assertEquals(2L, delete.getLong(0));
        assertEquals("beta", delete.getString(1).toString());
    }

    @Test
    @DisplayName("缺列 → 该字段 null（isNullAt），不抛异常")
    void missingColumnBecomesNull() {
        RowData row = mapper.map(cdcRecord("c", Map.<String, Object>of("id", 3L), null));
        assertEquals(3L, row.getLong(0));
        assertTrue(row.isNullAt(1));
        assertTrue(row.isNullAt(3));
    }

    @Test
    @DisplayName("append 模式收到 op=d → fail-fast（append 无删除语义）")
    void appendModeRejectsDelete() {
        ChangeRecordRowDataMapper appendMapper = new ChangeRecordRowDataMapper(SCHEMA, false);
        appendMapper.open(new Configuration());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> appendMapper.map(cdcRecord("d", null, Map.<String, Object>of("id", 9L))));
        assertTrue(ex.getMessage().contains("append"));
    }

    @Test
    @DisplayName("未知 op → fail-fast")
    void unknownOpFails() {
        assertThrows(IllegalStateException.class,
                () -> mapper.map(cdcRecord("x", Map.<String, Object>of("id", 1L), null)));
    }
}
