package com.shuqing.bigdata.encaps.security.facade.assessment;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 测评报告。
 *
 * <p>不可变值对象，封装一次测评导出的完整结果。</p>
 */
public final class AssessmentReport {

    private final String id;
    private final AssessmentType type;
    private final Instant generatedAt;
    private final String systemName;
    private final List<ControlItemStatus> controlItems;
    private final List<String> evidenceIds;
    private final String summary;

    /**
     * 全参构造。
     *
     * @param id           报告 ID
     * @param type         测评类型
     * @param generatedAt  生成时间
     * @param systemName   系统名称
     * @param controlItems 控制项状态列表
     * @param evidenceIds  证据 ID 列表
     * @param summary      总结
     */
    public AssessmentReport(String id, AssessmentType type, Instant generatedAt,
                            String systemName,
                            List<ControlItemStatus> controlItems,
                            List<String> evidenceIds, String summary) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
        this.systemName = systemName;
        this.controlItems = controlItems == null ? List.of() : List.copyOf(controlItems);
        this.evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        this.summary = summary;
    }

    public String getId() { return id; }
    public AssessmentType getType() { return type; }
    public Instant getGeneratedAt() { return generatedAt; }
    public String getSystemName() { return systemName; }
    public List<ControlItemStatus> getControlItems() { return controlItems; }
    public List<String> getEvidenceIds() { return evidenceIds; }
    public String getSummary() { return summary; }

    /**
     * 计算合规率（COMPLIANT + NOT_APPLICABLE）/ 总数。
     *
     * @return 0.0 ~ 1.0；无控制项时返回 0.0
     */
    public double complianceRate() {
        if (controlItems.isEmpty()) {
            return 0.0;
        }
        long passing = controlItems.stream()
                .filter(c -> c.getStatus().isPassing())
                .count();
        return (double) passing / controlItems.size();
    }

    /**
     * 转换为有序 Map（用于 JSON 序列化）。
     *
     * @return LinkedHashMap
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("type", type.getCode());
        map.put("typeDescription", type.getDescription());
        map.put("generatedAt", generatedAt.toString());
        map.put("systemName", systemName);
        map.put("complianceRate", complianceRate());
        map.put("controlItemCount", controlItems.size());
        map.put("evidenceCount", evidenceIds.size());
        map.put("summary", summary);
        map.put("controlItems", controlItems.stream().map(ControlItemStatus::toMap).toList());
        map.put("evidenceIds", evidenceIds);
        return map;
    }

    @Override
    public String toString() {
        return "AssessmentReport{id='" + id + "', type=" + type
                + ", generatedAt=" + generatedAt
                + ", controlItems=" + controlItems.size()
                + ", complianceRate=" + String.format("%.2f%%", complianceRate() * 100) + '}';
    }
}