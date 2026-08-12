package com.levango7.dataenginebdp.flinkcdc.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

/**
 * CDC 变更记录模型，兼容 Debezium JSON 格式。
 *
 * <p>每条记录描述一次源表变更事件，包含变更前/后的数据快照、操作类型、
 * 变更来源元数据（Binlog 文件名+位点/GTID）以及变更时间戳。</p>
 *
 * <p>Debezium JSON 结构示例：</p>
 * <pre>{@code
 * {
 *   "before": {"id": 1, "name": "old"},
 *   "after":  {"id": 1, "name": "new"},
 *   "op":     "u",
 *   "source": {"db": "shop", "table": "orders", "file": "binlog.000001", "pos": 1234},
 *   "ts_ms":  1700000000000
 * }
 * }</pre>
 *
 * @author shuqing-bigdata
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"before", "after", "op", "source", "ts_ms"})
public class ChangeRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 变更操作类型枚举，对应 Debezium {@code op} 字段。
     */
    public enum Op {

        /** 创建（Debezium: {@code c}）。 */
        INSERT("c"),

        /** 更新（Debezium: {@code u}）。 */
        UPDATE("u"),

        /** 删除（Debezium: {@code d}）。 */
        DELETE("d"),

        /** 快照读（Debezium: {@code r}），全量初始化阶段产生。 */
        SNAPSHOT("r");

        private final String code;

        Op(String code) {
            this.code = code;
        }

        /**
         * 获取 Debezium 单字母编码。
         *
         * @return Debezium op 编码 (c/u/d/r)
         */
        public String code() {
            return code;
        }

        /**
         * 根据 Debezium 编码解析为枚举值。
         *
         * @param code Debezium op 编码
         * @return 对应枚举值
         * @throws IllegalArgumentException 若编码不被识别
         */
        public static Op fromCode(String code) {
            Objects.requireNonNull(code, "op code 不能为 null");
            for (Op op : values()) {
                if (op.code.equalsIgnoreCase(code)) {
                    return op;
                }
            }
            throw new IllegalArgumentException("未知的 Debezium op 编码: " + code);
        }
    }

    /**
     * 变更前数据快照（INSERT 时为 {@code null}）。
     * <p>键为列名，值为列值（已按目标类型转换）。</p>
     */
    @JsonProperty("before")
    private Map<String, Object> before;

    /**
     * 变更后数据快照（DELETE 时为 {@code null}）。
     */
    @JsonProperty("after")
    private Map<String, Object> after;

    /**
     * 变更操作类型编码（c/u/d/r）。
     */
    @JsonProperty("op")
    private String op;

    /**
     * 变更来源元数据：db、table、file、pos、gtid 等。
     */
    @JsonProperty("source")
    private Map<String, Object> source;

    /**
     * 变更时间戳（毫秒，源端事务提交时间）。
     */
    @JsonProperty("ts_ms")
    private Long tsMs;

    /** 默认构造器，供反序列化使用。 */
    public ChangeRecord() {
    }

    /**
     * 全参构造器。
     *
     * @param before 变更前快照
     * @param after  变更后快照
     * @param op     操作类型
     * @param source 来源元数据
     * @param tsMs   变更时间戳
     */
    public ChangeRecord(Map<String, Object> before, Map<String, Object> after, Op op,
                        Map<String, Object> source, Long tsMs) {
        this.before = before;
        this.after = after;
        this.op = op == null ? null : op.code();
        this.source = source;
        this.tsMs = tsMs;
    }

    /**
     * 便捷构造器：使用字符串 op（兼容 Debezium 原始字段）。
     *
     * @param before 变更前快照
     * @param after  变更后快照
     * @param opCode Debezium op 编码 (c/u/d/r)
     * @param source 来源元数据
     * @param tsMs   变更时间戳
     */
    public ChangeRecord(Map<String, Object> before, Map<String, Object> after, String opCode,
                        Map<String, Object> source, Long tsMs) {
        this.before = before;
        this.after = after;
        this.op = opCode;
        this.source = source;
        this.tsMs = tsMs;
    }

    public Map<String, Object> getBefore() {
        return before;
    }

    public void setBefore(Map<String, Object> before) {
        this.before = before;
    }

    public Map<String, Object> getAfter() {
        return after;
    }

    public void setAfter(Map<String, Object> after) {
        this.after = after;
    }

    public String getOp() {
        return op;
    }

    public void setOp(String op) {
        this.op = op;
    }

    public Map<String, Object> getSource() {
        return source;
    }

    public void setSource(Map<String, Object> source) {
        this.source = source;
    }

    @JsonProperty("ts_ms")
    public Long getTsMs() {
        return tsMs;
    }

    @JsonProperty("ts_ms")
    public void setTsMs(Long tsMs) {
        this.tsMs = tsMs;
    }

    /**
     * 获取操作类型枚举形式。
     *
     * @return 操作类型枚举；若 op 为 null 或无法识别则返回 {@code null}
     */
    public Op opEnum() {
        if (op == null) {
            return null;
        }
        try {
            return Op.fromCode(op);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 判断是否为 INSERT 操作。
     *
     * @return 若 op 为 {@code c} 返回 true
     */
    public boolean isInsert() {
        return Op.INSERT.code().equals(op);
    }

    /**
     * 判断是否为 UPDATE 操作。
     *
     * @return 若 op 为 {@code u} 返回 true
     */
    public boolean isUpdate() {
        return Op.UPDATE.code().equals(op);
    }

    /**
     * 判断是否为 DELETE 操作。
     *
     * @return 若 op 为 {@code d} 返回 true
     */
    public boolean isDelete() {
        return Op.DELETE.code().equals(op);
    }

    /**
     * 判断是否为快照读（全量初始化阶段）。
     *
     * @return 若 op 为 {@code r} 返回 true
     */
    public boolean isSnapshot() {
        return Op.SNAPSHOT.code().equals(op);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChangeRecord that)) {
            return false;
        }
        return Objects.equals(before, that.before)
                && Objects.equals(after, that.after)
                && Objects.equals(op, that.op)
                && Objects.equals(source, that.source)
                && Objects.equals(tsMs, that.tsMs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(before, after, op, source, tsMs);
    }

    @Override
    public String toString() {
        return "ChangeRecord{op='" + op + "', tsMs=" + tsMs
                + ", before=" + before + ", after=" + after
                + ", source=" + source + '}';
    }
}