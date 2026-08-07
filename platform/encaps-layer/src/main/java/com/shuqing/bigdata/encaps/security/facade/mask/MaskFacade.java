package com.shuqing.bigdata.encaps.security.facade.mask;

import com.shuqing.bigdata.encaps.security.facade.config.SecurityFacadeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 脱敏统一门面（MaskFacade）。
 *
 * <p>对外暴露按 {@link MaskType} 脱敏的简化 API，内置 8 种常见敏感字段规则，
 * 并支持运行时注册 {@link MaskType#CUSTOM} 自定义规则。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * String masked = maskFacade.mask("13812345678", MaskType.PHONE);
 * // → "138****5678"
 *
 * maskFacade.registerCustom("myRule", input -> input.substring(0, 2) + "***");
 * String masked2 = maskFacade.maskCustom("abcdef", "myRule");
 * // → "ab***"
 * }</pre>
 *
 * <h3>线程安全</h3>
 * <p>内置规则无状态；自定义规则存储在 {@link ConcurrentHashMap}，支持并发注册与调用。</p>
 */
@Component
public class MaskFacade {

    private static final Logger log = LoggerFactory.getLogger(MaskFacade.class);

    /** 内置规则（按 MaskType 索引） */
    private final Map<MaskType, MaskRule> builtInRules;

    /** 自定义规则（按名称索引） */
    private final Map<String, MaskRule> customRules = new ConcurrentHashMap<>();

    private final SecurityFacadeConfig config;

    /**
     * 构造 MaskFacade，加载所有内置规则。
     *
     * @param config SecurityFacade 配置
     */
    public MaskFacade(SecurityFacadeConfig config) {
        this.config = config;
        this.builtInRules = BuiltInMaskRules.all();
        log.info("MaskFacade initialized with {} built-in rules", builtInRules.size());
    }

    /**
     * 按类型脱敏。
     *
     * @param input 原始字符串
     * @param type  脱敏类型
     * @return 脱敏后字符串
     * @throws IllegalStateException 脱敏能力被禁用
     * @throws IllegalArgumentException 类型未注册
     */
    public String mask(String input, MaskType type) {
        ensureEnabled();
        if (type == MaskType.CUSTOM) {
            throw new IllegalArgumentException("For CUSTOM type, use maskCustom(input, ruleName)");
        }
        MaskRule rule = builtInRules.get(type);
        if (rule == null) {
            throw new IllegalArgumentException("No built-in rule for MaskType: " + type);
        }
        return rule.mask(input);
    }

    /**
     * 按自定义规则名脱敏。
     *
     * @param input    原始字符串
     * @param ruleName 自定义规则名
     * @return 脱敏后字符串
     * @throws IllegalStateException  脱敏能力被禁用
     * @throws IllegalArgumentException 规则未注册
     */
    public String maskCustom(String input, String ruleName) {
        ensureEnabled();
        MaskRule rule = customRules.get(ruleName);
        if (rule == null) {
            throw new IllegalArgumentException("Custom mask rule not found: " + ruleName
                    + ", registered: " + customRules.keySet());
        }
        return rule.mask(input);
    }

    /**
     * 注册自定义脱敏规则。
     *
     * @param name 规则名（唯一）
     * @param rule 规则实现
     * @throws IllegalArgumentException name 或 rule 为空
     */
    public void registerCustom(String name, MaskRule rule) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("rule name must not be blank");
        }
        if (rule == null) {
            throw new IllegalArgumentException("rule must not be null");
        }
        customRules.put(name, rule);
        log.info("Registered custom mask rule: {}", name);
    }

    /**
     * 注销自定义规则。
     *
     * @param name 规则名
     * @return 是否存在并移除
     */
    public boolean unregisterCustom(String name) {
        return customRules.remove(name) != null;
    }

    /**
     * 列出已注册自定义规则名。
     *
     * @return 规则名集合
     */
    public java.util.Set<String> customRuleNames() {
        return java.util.Collections.unmodifiableSet(customRules.keySet());
    }

    /**
     * 列出所有内置规则类型。
     *
     * @return MaskType 集合
     */
    public java.util.Set<MaskType> builtInTypes() {
        return java.util.Collections.unmodifiableSet(builtInRules.keySet());
    }

    private void ensureEnabled() {
        if (!config.isEnabled() || !config.getMask().isEnabled()) {
            throw new IllegalStateException("MaskFacade is disabled (app.security.facade.enabled="
                    + config.isEnabled() + ", mask.enabled=" + config.getMask().isEnabled() + ")");
        }
    }
}