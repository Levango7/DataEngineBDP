package com.levango7.dataenginebdp.encaps.security.facade.assessment;

/**
 * 合规状态枚举。
 *
 * <p>表示某一控制项相对测评标准的合规情况。</p>
 */
public enum ComplianceStatus {

    /** 完全合规：控制项已实施且有效 */
    COMPLIANT("符合"),

    /** 部分合规：控制项已实施但存在不足 */
    PARTIAL("部分符合"),

    /** 不合规：控制项未实施或无效 */
    NON_COMPLIANT("不符合"),

    /** 不适用：当前系统不涉及该控制项 */
    NOT_APPLICABLE("不适用");

    private final String label;

    ComplianceStatus(String label) {
        this.label = label;
    }

    /**
     * 中文标签（用于报告输出）。
     *
     * @return 标签
     */
    public String getLabel() { return label; }

    /**
     * 是否视为通过（COMPLIANT 或 NOT_APPLICABLE）。
     *
     * @return true 表示通过
     */
    public boolean isPassing() {
        return this == COMPLIANT || this == NOT_APPLICABLE;
    }
}