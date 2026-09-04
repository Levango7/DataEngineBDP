package com.levango7.dataenginebdp.encaps.common;

/**
 * 统一业务错误码枚举。
 *
 * <p>编码规则：HTTP 状态码 + 两位业务序号。
 * <ul>
 *   <li>4xxxx：客户端错误（4xx）</li>
 *   <li>5xxxx：服务端错误（5xx）</li>
 * </ul>
 *
 * <p>前端 {@code client.ts} 约定：{@code code != 0} 视为业务失败，
 * 触发 {@code errorNotifier} 并 reject Promise。</p>
 *
 * <p>A2 错误国际化：每个错误码附带 {@code messageKey}（点分命名，如
 * {@code error.param.invalid}），随 ApiResponse 下发；前端优先用
 * {@code messageKey} 经 vue-i18n 翻译（找不到词条回退 {@code message} 原文），
 * 保证中英文切换时错误提示与 UI 语种一致。</p>
 */
public enum ErrorCode {

    /** 成功 */
    SUCCESS(0, "OK", null),

    /** 参数校验失败 */
    PARAM_INVALID(40001, "参数校验失败", "error.param.invalid"),

    /** 缺少必要参数 */
    PARAM_MISSING(40002, "缺少必要参数", "error.param.missing"),

    /** 参数类型错误 */
    PARAM_TYPE_ERROR(40003, "参数类型错误", "error.param.type"),

    /** 未认证或 token 过期 */
    UNAUTHORIZED(40101, "未认证或 token 过期", "error.auth.unauthorized"),

    /** 无权限访问 */
    FORBIDDEN(40301, "无权限", "error.auth.forbidden"),

    /** 资源不存在 */
    NOT_FOUND(40401, "资源不存在", "error.resource.notFound"),

    /** 请求方法不支持 */
    METHOD_NOT_ALLOWED(40501, "请求方法不支持", "error.method.notAllowed"),

    /** 资源冲突（如重复创建） */
    CONFLICT(40901, "资源冲突", "error.resource.conflict"),

    /** 请求过于频繁 */
    RATE_LIMITED(42901, "请求过于频繁", "error.rate.limited"),

    /** 内部错误 */
    INTERNAL_ERROR(50001, "内部错误", "error.internal"),

    /** 数据库错误 */
    DB_ERROR(50002, "数据库错误", "error.db"),

    /** 外部服务调用失败 */
    EXT_SERVICE_ERROR(50003, "外部服务调用失败", "error.extService"),

    /** 序列化/反序列化失败 */
    SERIALIZE_ERROR(50004, "数据序列化失败", "error.serialize");

    private final int code;
    private final String message;
    private final String messageKey;

    ErrorCode(int code, String message, String messageKey) {
        this.code = code;
        this.message = message;
        this.messageKey = messageKey;
    }

    /**
     * 获取错误码。
     *
     * @return 错误码
     */
    public int getCode() {
        return code;
    }

    /**
     * 获取错误消息（中文兜底文案）。
     *
     * @return 错误消息
     */
    public String getMessage() {
        return message;
    }

    /**
     * 获取 i18n 消息键（前端翻译用；SUCCESS 为 null）。
     *
     * @return 点分消息键，如 {@code error.param.invalid}
     */
    public String getMessageKey() {
        return messageKey;
    }
}
