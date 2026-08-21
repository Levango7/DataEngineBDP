package com.shuqing.bigdata.encaps.security.facade.mask;

/**
 * 脱敏规则接口。
 *
 * <p>每种规则负责将原始字符串转换为脱敏后的字符串。
 * 实现需满足：</p>
 * <ol>
 *   <li>纯函数：相同输入始终产生相同输出</li>
 *   <li>幂等：对已脱敏结果再次脱敏应返回相同结果</li>
 *   <li>空安全：null 或空字符串应原样返回</li>
 *   <li>线程安全：无共享可变状态</li>
 * </ol>
 */
public interface MaskRule {

    /**
     * 该规则支持的脱敏类型。
     *
     * @return MaskType 枚举
     */
    MaskType supportedType();

    /**
     * 执行脱敏。
     *
     * @param input 原始字符串；可为 null
     * @return 脱敏后字符串；input 为 null 时返回 null
     */
    String mask(String input);
}