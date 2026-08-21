package com.shuqing.bigdata.encaps.security.facade.mask;

/**
 * 脱敏类型枚举。
 *
 * <p>覆盖等保 2.0 与《个人信息保护法》常见敏感字段类型，
 * 每种类型对应一个内置 {@link MaskRule} 实现。</p>
 */
public enum MaskType {

    /** 手机号：保留前 3 后 4，中间 4 位脱敏，如 138****5678 */
    PHONE,

    /** 身份证号：保留前 6 后 4，中间 8 位脱敏，如 110101********1234 */
    ID_CARD,

    /** 银行卡号：保留前 4 后 4，中间脱敏，如 6222****1234 */
    BANK_CARD,

    /** 邮箱：保留首字符与 @ 后域名，如 z***@example.com */
    EMAIL,

    /** 姓名：保留姓氏，名脱敏，如 张** */
    NAME,

    /** 地址：保留前 6 字符，其余脱敏 */
    ADDRESS,

    /** IP 地址：保留前两段，后两段脱敏，如 192.168.*.* */
    IP,

    /** 全脱敏：所有字符替换为 * */
    FULL,

    /** 自定义：使用注册的 MaskRule 实现 */
    CUSTOM
}