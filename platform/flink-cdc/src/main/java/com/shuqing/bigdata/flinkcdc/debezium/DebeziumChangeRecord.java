package com.shuqing.bigdata.flinkcdc.debezium;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.shuqing.bigdata.flinkcdc.model.ChangeRecord;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

/**
 * Debezium 变更记录扩展模型，在 {@link ChangeRecord} 基础上增加 Debezium 特有元数据。
 *
 * <p>额外字段：</p>
 * <ul>
 *   <li>{@code schema} — Debezium 事件 schema 信息（payload schema / 顶层 schema）</li>
 *   <li>{@code sourceMeta} — Debezium source 元数据中的扩展字段（如 {@code snapshot} 标识、
 *       {@code dbserver_name}、{@code lsn}、{@code txId} 等）</li>
 *   <li>{@code transaction} — 事务边界信息（事务 ID / 总事件数 / 当前事件序号）</li>
 * </ul>
 *
 * <p>典型 Debezium JSON 结构：</p>
 * <pre>{@code
 * {
 *   "schema": {...},
 *   "payload": {
 *     "before": {...},
 *     "after":  {...},
 *     "source": {"db":"shop","table":"orders","ts_ms":1700000000000,"snapshot":"false"},
 *     "op": "u",
 *     "ts_ms": 1700000000000,
 *     "transaction": {"id":"tx-1","total_order":3,"data_collection_order":1}
 *   }
 * }
 * }</pre>
 *
 * @author shuqing-bigdata
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"before", "after", "op", "source", "ts_ms", "schema", "sourceMeta", "transaction"})
public class DebeziumChangeRecord extends ChangeRecord implements Serializable {

    private static final long serialVersionUID = 2L;

    /**
     * Debezium 事件 schema 描述（来自 {@code schema} 字段）。
     * <p>键为 schema 字段名（如 {@code type}、{@code optional}、{@code fields}、{@code name}），
     * 值为对应 schema 描述对象。</p>
     */
    @JsonProperty("schema")
    private Map<String, Object> schema;

    /**
     * Debezium source 元数据中的扩展字段（除 db/table/file/pos 之外的部分）。
     * <p>典型字段：{@code snapshot}（"true"/"false"/"last"）、{@code dbserver_name}、
     * {@code lsn}、{@code txId}、{@code thread} 等。</p>
     */
    @JsonProperty("sourceMeta")
    private Map<String, Object> sourceMeta;

    /**
     * 事务边界信息（Debezium 1.2+ 启用 {@code transaction.metadata} 后产生）。
     * <p>典型字段：{@code id}（事务 ID）、{@code total_order}（事务内总事件序号）、
     * {@code data_collection_order}（数据事件序号）。</p>
     */
    @JsonProperty("transaction")
    private Map<String, Object> transaction;

    /** 默认构造器，供反序列化使用。 */
    public DebeziumChangeRecord() {
        super();
    }

    /**
     * 全参构造器。
     *
     * @param before      变更前快照
     * @param after       变更后快照
     * @param opCode      Debezium op 编码 (c/u/d/r)
     * @param source      来源元数据
     * @param tsMs        变更时间戳
     * @param schema      schema 描述
     * @param sourceMeta  source 扩展元数据
     * @param transaction 事务边界信息
     */
    public DebeziumChangeRecord(Map<String, Object> before, Map<String, Object> after,
                                String opCode, Map<String, Object> source, Long tsMs,
                                Map<String, Object> schema, Map<String, Object> sourceMeta,
                                Map<String, Object> transaction) {
        super(before, after, opCode, source, tsMs);
        this.schema = schema;
        this.sourceMeta = sourceMeta;
        this.transaction = transaction;
    }

    /**
     * 从基础 {@link ChangeRecord} 升级为 {@link DebeziumChangeRecord}（保留所有基础字段）。
     *
     * @param record 基础变更记录
     * @return DebeziumChangeRecord（不含额外元数据）
     */
    public static DebeziumChangeRecord from(ChangeRecord record) {
        Objects.requireNonNull(record, "ChangeRecord 不能为 null");
        DebeziumChangeRecord ext = new DebeziumChangeRecord();
        ext.setBefore(record.getBefore());
        ext.setAfter(record.getAfter());
        ext.setOp(record.getOp());
        ext.setSource(record.getSource());
        ext.setTsMs(record.getTsMs());
        return ext;
    }

    public Map<String, Object> getSchema() {
        return schema;
    }

    public void setSchema(Map<String, Object> schema) {
        this.schema = schema;
    }

    public Map<String, Object> getSourceMeta() {
        return sourceMeta;
    }

    public void setSourceMeta(Map<String, Object> sourceMeta) {
        this.sourceMeta = sourceMeta;
    }

    public Map<String, Object> getTransaction() {
        return transaction;
    }

    public void setTransaction(Map<String, Object> transaction) {
        this.transaction = transaction;
    }

    /**
     * 判断当前事件是否为快照阶段（source.snapshot 字段为 "true" 或 "last"）。
     *
     * @return 若 source 或 sourceMeta 中 snapshot 字段为 "true"/"last" 返回 true
     */
    public boolean isSnapshotEvent() {
        if (isSnapshot()) {
            return true;
        }
        String snapshot = readSnapshotFlag();
        return "true".equalsIgnoreCase(snapshot) || "last".equalsIgnoreCase(snapshot);
    }

    private String readSnapshotFlag() {
        if (getSource() != null) {
            Object s = getSource().get("snapshot");
            if (s != null) {
                return String.valueOf(s);
            }
        }
        if (sourceMeta != null) {
            Object s = sourceMeta.get("snapshot");
            if (s != null) {
                return String.valueOf(s);
            }
        }
        return null;
    }

    /**
     * 获取事务 ID（若启用 transaction.metadata）。
     *
     * @return 事务 ID；不存在返回 {@code null}
     */
    public String transactionId() {
        if (transaction == null) {
            return null;
        }
        Object id = transaction.get("id");
        return id == null ? null : String.valueOf(id);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DebeziumChangeRecord that)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        return Objects.equals(schema, that.schema)
                && Objects.equals(sourceMeta, that.sourceMeta)
                && Objects.equals(transaction, that.transaction);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), schema, sourceMeta, transaction);
    }

    @Override
    public String toString() {
        return "DebeziumChangeRecord{op='" + getOp() + "', tsMs=" + getTsMs()
                + ", before=" + getBefore() + ", after=" + getAfter()
                + ", source=" + getSource()
                + ", schema=" + (schema == null ? "null" : "present")
                + ", transaction=" + transaction + '}';
    }
}