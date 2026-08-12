package com.levango7.dataenginebdp.encaps.security.facade.assessment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 控制项状态。
 *
 * <p>不可变值对象，表示一个测评控制项（如等保 8.1.4.3 安全审计）的当前状态。</p>
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>{@code controlId}    — 控制项编号，如 {@code 8.1.4.3}</li>
 *   <li>{@code title}        — 控制项名称，如 {@code 安全审计}</li>
 *   <li>{@code requirement}  — 控制项要求描述</li>
 *   <li>{@code status}       — 合规状态</li>
 *   <li>{@code evidenceIds}  — 关联证据 ID 列表</li>
 *   <li>{@code gap}          — 差距分析说明（status 非 COMPLIANT 时填充）</li>
 *   <li>{@code remediation}  — 整改建议</li>
 * </ul>
 */
public final class ControlItemStatus {

    private final String controlId;
    private final String title;
    private final String requirement;
    private final ComplianceStatus status;
    private final List<String> evidenceIds;
    private final String gap;
    private final String remediation;

    /**
     * 全参构造。
     *
     * @param controlId   控制项编号
     * @param title       名称
     * @param requirement 要求描述
     * @param status      合规状态
     * @param evidenceIds 证据 ID 列表
     * @param gap         差距说明
     * @param remediation 整改建议
     */
    public ControlItemStatus(String controlId, String title, String requirement,
                             ComplianceStatus status, List<String> evidenceIds,
                             String gap, String remediation) {
        this.controlId = Objects.requireNonNull(controlId, "controlId");
        this.title = Objects.requireNonNull(title, "title");
        this.requirement = requirement;
        this.status = Objects.requireNonNull(status, "status");
        this.evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        this.gap = gap;
        this.remediation = remediation;
    }

    public String getControlId() { return controlId; }
    public String getTitle() { return title; }
    public String getRequirement() { return requirement; }
    public ComplianceStatus getStatus() { return status; }
    public List<String> getEvidenceIds() { return evidenceIds; }
    public String getGap() { return gap; }
    public String getRemediation() { return remediation; }

    /**
     * 转换为有序 Map（用于 JSON 序列化）。
     *
     * @return LinkedHashMap
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("controlId", controlId);
        map.put("title", title);
        map.put("requirement", requirement);
        map.put("status", status.name());
        map.put("statusLabel", status.getLabel());
        map.put("evidenceIds", evidenceIds);
        if (gap != null) map.put("gap", gap);
        if (remediation != null) map.put("remediation", remediation);
        return map;
    }

    @Override
    public String toString() {
        return "ControlItemStatus{" + controlId + " " + title
                + ", status=" + status
                + ", evidenceCount=" + evidenceIds.size() + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ControlItemStatus that)) return false;
        return Objects.equals(controlId, that.controlId)
                && Objects.equals(title, that.title)
                && Objects.equals(requirement, that.requirement)
                && status == that.status
                && Objects.equals(evidenceIds, that.evidenceIds)
                && Objects.equals(gap, that.gap)
                && Objects.equals(remediation, that.remediation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(controlId, title, requirement, status, evidenceIds, gap, remediation);
    }
}