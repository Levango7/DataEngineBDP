package com.levango7.dataenginebdp.flinkcdc.sink;

import com.levango7.dataenginebdp.flinkcdc.model.ChangeRecord;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.types.RowKind;
import org.apache.iceberg.Schema;
import org.apache.iceberg.types.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * ChangeRecord → RowData mapper（A1 终段：真实 Iceberg 写入的转换面）。
 *
 * <p>Schema 在作业构建期由连接器从 catalog 实际加载后注入（attach 时 fail-fast：
 * 表不存在/字段不匹配即提交失败），运行期不再查 catalog——与 Iceberg sink
 * 「plan 期定 schema」的语义一致。</p>
 *
 * <p>RowKind 映射（UPSERT 语义）：{@code c}/{@code r} → INSERT；
 * {@code u} → UPDATE_AFTER；{@code d} → DELETE（append 模式收到 d 即抛异常 fail-fast，
 * 因为 append 不支持删除语义）。</p>
 *
 * <p>列值转换按 {@link Types.NestedField#fieldType()} 的 typeId 分派：
 * STRING/LONG/INTEGER/DOUBLE/FLOAT/BOOLEAN/TIMESTAMP/DATE/DECIMAL 予以支持，
 * 其余类型抛异常 fail-fast（宁可不写，不写错类型）。</p>
 */
public class ChangeRecordRowDataMapper extends RichMapFunction<ChangeRecord, RowData> {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(ChangeRecordRowDataMapper.class);

    private final Schema schema;
    private final boolean upsert;

    private transient List<Types.NestedField> columns;

    public ChangeRecordRowDataMapper(Schema schema, boolean upsert) {
        this.schema = schema;
        this.upsert = upsert;
    }

    @Override
    public void open(Configuration parameters) {
        columns = schema.columns();
        log.info("ChangeRecordRowDataMapper 就绪: {} 列, upsert={}", columns.size(), upsert);
    }

    @Override
    public RowData map(ChangeRecord record) {
        String op = record.getOp();
        boolean isDelete = "d".equalsIgnoreCase(op);
        if (isDelete && !upsert) {
            throw new IllegalStateException(
                    "append 模式不支持删除语义（op=d）：请将 SinkConfig.write-mode 设为 upsert（Iceberg V2）");
        }
        Map<String, Object> row = isDelete
                ? (record.getBefore() != null ? record.getBefore() : record.getAfter())
                : (record.getAfter() != null ? record.getAfter() : record.getBefore());
        if (row == null) {
            throw new IllegalStateException(
                    "变更记录缺少 before/after 数据（op=" + op + "），无法构造 RowData");
        }

        GenericRowData out = new GenericRowData(columns.size());
        out.setRowKind(rowKind(op));
        for (int i = 0; i < columns.size(); i++) {
            Types.NestedField field = columns.get(i);
            Object value = row.get(field.name());
            out.setField(i, value == null ? null : convert(field, value));
        }
        return out;
    }

    private RowKind rowKind(String op) {
        if (op == null) {
            throw new IllegalStateException("ChangeRecord 缺少 op 字段");
        }
        switch (op.toLowerCase()) {
            case "c":
            case "r":
                return RowKind.INSERT;
            case "u":
                return RowKind.UPDATE_AFTER;
            case "d":
                return RowKind.DELETE;
            default:
                throw new IllegalStateException("未知的 Debezium op 编码: " + op);
        }
    }

    /** 按 Iceberg 列类型做窄转换（null 由调用方处理，此处值非 null）。 */
    private Object convert(Types.NestedField field, Object value) {
        switch (field.type().typeId()) {
            case STRING:
                return StringData.fromString(String.valueOf(value));
            case LONG:
                return ((Number) value).longValue();
            case INTEGER:
                return ((Number) value).intValue();
            case DOUBLE:
                return ((Number) value).doubleValue();
            case FLOAT:
                return ((Number) value).floatValue();
            case BOOLEAN:
                if (value instanceof Boolean) {
                    return value;
                }
                return Boolean.parseBoolean(String.valueOf(value));
            case TIMESTAMP: {
                // 优先 epoch millis（Debezium ts 口径）；ISO 字符串须兼容时区指示符
                // 尾巴与变长小数位（pgoutput 的 timestamptz 形如
                // 2026-11-14T23:35:48.719472Z，端到端泳道实证 ISO_LOCAL_DATE_TIME
                // 解析撞「unparsed text」）；withZone 字段（TIMESTAMP_LTZ）的
                // RowData 语义 = UTC epoch millis
                Types.TimestampType tsType = (Types.TimestampType) field.type();
                if (value instanceof Number) {
                    return TimestampData.fromEpochMillis(((Number) value).longValue());
                }
                String text = String.valueOf(value);
                if (text.endsWith("Z") || text.matches(".*[+-]\\d{2}:?\\d{2}$")) {
                    OffsetDateTime odt = OffsetDateTime.parse(text);
                    return tsType.shouldAdjustToUTC()
                            ? TimestampData.fromEpochMillis(odt.toInstant().toEpochMilli())
                            : TimestampData.fromLocalDateTime(odt.toLocalDateTime());
                }
                return TimestampData.fromLocalDateTime(
                        LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            }
            case DATE:
                if (value instanceof Number) {
                    return (int) ((Number) value).longValue(); // epoch days
                }
                return (int) LocalDate.parse(String.valueOf(value), DateTimeFormatter.ISO_LOCAL_DATE).toEpochDay();
            case DECIMAL:
                return value instanceof BigDecimal ? value : new BigDecimal(String.valueOf(value));
            default:
                throw new IllegalStateException(
                        "Iceberg 列「" + field.name() + "」类型不支持 CDC 写入: " + field.type());
        }
    }
}
