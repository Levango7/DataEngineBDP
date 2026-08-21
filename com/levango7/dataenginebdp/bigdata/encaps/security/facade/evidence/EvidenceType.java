package com.shuqing.bigdata.encaps.security.facade.evidence;

/**
 * 证据类型枚举。
 *
 * <p>对应等保 2.0 与密评测评中需要提交的合规证据类别。</p>
 */
public enum EvidenceType {

    /** 操作日志：来自 AuditFacade 的事件 */
    AUDIT_LOG,

    /** 配置快照：安全相关配置的某一时刻快照 */
    CONFIG_SNAPSHOT,

    /** 审计记录：归档后的审计批次 */
    AUDIT_RECORD,

    /** 加解密操作记录 */
    CRYPTO_OPERATION,

    /** 脱敏操作记录 */
    MASK_OPERATION,

    /** 鉴权决策记录 */
    AUTH_DECISION,

    /** 控制项自评结果 */
    CONTROL_ITEM_STATUS
}