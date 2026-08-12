package com.levango7.dataenginebdp.encaps.security.facade.audit;

/**
 * 审计事件级别。
 *
 * <p>对应等保 2.0 安全审计控制项（8.1.4.3）要求的事件分级，
 * 便于后续按级别过滤、告警与归档。</p>
 */
public enum AuditLevel {

    /** 信息级：常规操作日志，如登录成功、数据查询 */
    INFO,

    /** 警告级：可疑操作，如鉴权失败重试、敏感数据批量访问 */
    WARN,

    /** 错误级：操作失败，如加密失败、配置加载失败 */
    ERROR,

    /** 严重级：安全事件，如越权访问、密钥泄露检测 */
    CRITICAL
}