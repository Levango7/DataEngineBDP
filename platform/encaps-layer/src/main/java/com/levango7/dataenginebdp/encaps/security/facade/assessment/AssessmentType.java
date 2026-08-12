package com.levango7.dataenginebdp.encaps.security.facade.assessment;

/**
 * 测评类型枚举。
 *
 * <p>对应国内主要安全合规测评标准。</p>
 */
public enum AssessmentType {

    /** 等级保护 2.0（GB/T 22239-2019） */
    DENGBAO_2_0("dengbao-2.0", "网络安全等级保护基本要求 2.0"),

    /** 密码应用安全性评估（GM/T 0054-2018） */
    MIPING("miping", "信息系统密码应用基本要求"),

    /** 个人信息保护影响评估（PIPIA） */
    PIPIA("pipia", "个人信息保护影响评估");

    private final String code;
    private final String description;

    AssessmentType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 标准代码（用于配置与文件名）。
     *
     * @return 代码字符串
     */
    public String getCode() { return code; }

    /**
     * 标准描述。
     *
     * @return 描述
     */
    public String getDescription() { return description; }

    /**
     * 由代码解析枚举。
     *
     * @param code 代码
     * @return 枚举
     * @throws IllegalArgumentException 未知代码
     */
    public static AssessmentType fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("assessment code must not be blank");
        }
        for (AssessmentType t : values()) {
            if (t.code.equalsIgnoreCase(code.trim())) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown assessment code: " + code);
    }
}