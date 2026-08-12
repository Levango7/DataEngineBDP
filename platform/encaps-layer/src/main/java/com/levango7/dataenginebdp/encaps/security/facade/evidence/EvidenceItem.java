package com.levango7.dataenginebdp.encaps.security.facade.evidence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 证据项。
 *
 * <p>不可变值对象，表示一份合规证据的元数据与内容。
 * 由 {@link EvidenceCollector} 生成，由 {@link EvidenceArchive} 持久化。</p>
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>{@code id}          — 证据唯一 ID（UUID）</li>
 *   <li>{@code type}        — 证据类型</li>
 *   <li>{@code timestamp}   — 收集时间</li>
 *   <li>{@code description} — 证据描述</li>
 *   <li>{@code source}      — 证据来源（如 {@code AuditFacade} / {@code CryptoFacade}）</li>
 *   <li>{@code content}     — 证据内容（结构化键值对）</li>
 *   <li>{@code checksum}    — 内容校验和（SHA-256，由归档时计算）</li>
 * </ul>
 */
public final class EvidenceItem {

    private final String id;
    private final EvidenceType type;
    private final Instant timestamp;
    private final String description;
    private final String source;
    private final Map<String, Object> content;
    private final String checksum;

    /**
     * 全参构造。
     *
     * @param id          证据 ID
     * @param type        证据类型
     * @param timestamp   收集时间
     * @param description 描述
     * @param source      来源
     * @param content     内容（会被拷贝为不可变 Map）
     * @param checksum    校验和（可空，归档时填充）
     */
    public EvidenceItem(String id, EvidenceType type, Instant timestamp,
                        String description, String source,
                        Map<String, Object> content, String checksum) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.description = description;
        this.source = source;
        // 使用 LinkedHashMap 而非 Map.copyOf，因为 content 可能包含 null 值
        // （如 AuditEvent 的 tenantId/userId 可能为 null），Map.copyOf 不允许 null
        this.content = content == null
                ? java.util.Collections.emptyMap()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(content));
        this.checksum = checksum;
    }

    public String getId() { return id; }
    public EvidenceType getType() { return type; }
    public Instant getTimestamp() { return timestamp; }
    public String getDescription() { return description; }
    public String getSource() { return source; }
    public Map<String, Object> getContent() { return content; }
    public String getChecksum() { return checksum; }

    /**
     * 转换为有序 Map（便于 JSON 序列化与归档）。
     *
     * @return LinkedHashMap
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("type", type.name());
        map.put("timestamp", timestamp.toString());
        map.put("description", description);
        map.put("source", source);
        map.put("content", content);
        map.put("checksum", checksum);
        return map;
    }

    @Override
    public String toString() {
        return "EvidenceItem{id='" + id + "', type=" + type
                + ", timestamp=" + timestamp
                + ", source='" + source + '\''
                + ", checksum='" + checksum + '\'' + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EvidenceItem that)) return false;
        return Objects.equals(id, that.id)
                && type == that.type
                && Objects.equals(timestamp, that.timestamp)
                && Objects.equals(description, that.description)
                && Objects.equals(source, that.source)
                && Objects.equals(content, that.content)
                && Objects.equals(checksum, that.checksum);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, timestamp, description, source, content, checksum);
    }
}