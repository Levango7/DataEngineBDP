package com.levango7.dataenginebdp.ruleengine.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;

/**
 * 脱敏函数库。
 *
 * <p>提供四种脱敏策略，表达式格式为 {@code <strategy>[:params]}：
 * <ul>
 *   <li><b>mask</b> {@code mask[:keepPrefix,keepSuffix]} — 掩码策略，保留前后指定位，
 *       中间替换为 {@code *}。默认 keepPrefix=1, keepSuffix=1。
 *       示例：{@code mask:3,4} 对 {@code 13812345678} → {@code 138****5678}</li>
 *   <li><b>hash</b> {@code hash[:algorithm]} — 哈希策略，默认 SHA-256，返回十六进制摘要。
 *       示例：{@code hash:SHA-512}</li>
 *   <li><b>replace</b> {@code replace[:replacement]} — 整体替换为指定字符串，默认 {@code ***}。
 *       示例：{@code replace:[REDACTED]}</li>
 *   <li><b>pseudonymize</b> — 假名化策略，生成与输入等长的随机小写字母字符串，
 *       保留数据形态但不暴露原值</li>
 * </ul>
 * 该类为无状态工具类（除 {@link SecureRandom} 外），线程安全。</p>
 */
final class MaskFunctions {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DEFAULT_MASK_CHAR = "*";
    private static final String PSEUDONYM_ALPHABET = "abcdefghijklmnopqrstuvwxyz";

    private MaskFunctions() {
        // 工具类，禁止实例化
    }

    /**
     * 从执行上下文提取待脱敏输入值。
     * 优先级：{@code input} > {@code value} > {@code column}。
     *
     * @param context 执行上下文
     * @return 输入值；不存在时返回 null
     */
    static Object extractInput(Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        if (context.containsKey("input")) {
            return context.get("input");
        }
        if (context.containsKey("value")) {
            return context.get("value");
        }
        return context.get("column");
    }

    /** 解析策略名（冒号前的部分）。 */
    static String parseStrategy(String expression) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        int idx = expression.indexOf(':');
        return idx < 0 ? expression.trim() : expression.substring(0, idx).trim();
    }

    /**
     * 应用脱敏策略。
     *
     * @param expression 脱敏表达式，如 {@code mask:3,4}
     * @param input      原始输入字符串
     * @return 脱敏后字符串
     * @throws IllegalArgumentException 当策略未知或参数格式错误时
     */
    static String apply(String expression, String input) {
        String strategy = parseStrategy(expression);
        String params = extractParams(expression);
        return switch (strategy) {
            case "mask" -> mask(input, params);
            case "hash" -> hash(input, params);
            case "replace" -> replace(input, params);
            case "pseudonymize" -> pseudonymize(input);
            default -> throw new IllegalArgumentException("unknown mask strategy: " + strategy);
        };
    }

    private static String extractParams(String expression) {
        if (expression == null) {
            return "";
        }
        int idx = expression.indexOf(':');
        return idx < 0 ? "" : expression.substring(idx + 1);
    }

    /**
     * 掩码策略：保留前 keepPrefix 位和后 keepSuffix 位，中间替换为 *。
     *
     * @param input  原始输入
     * @param params 格式 "keepPrefix,keepSuffix"，默认 "1,1"
     */
    static String mask(String input, String params) {
        if (input == null) {
            return null;
        }
        int keepPrefix = 1;
        int keepSuffix = 1;
        if (params != null && !params.isBlank()) {
            String[] parts = params.split(",");
            if (parts.length >= 1 && !parts[0].isBlank()) {
                keepPrefix = Integer.parseInt(parts[0].trim());
            }
            if (parts.length >= 2 && !parts[1].isBlank()) {
                keepSuffix = Integer.parseInt(parts[1].trim());
            }
        }
        int len = input.length();
        if (len <= keepPrefix + keepSuffix) {
            // 输入过短，全量掩码以避免泄露
            return DEFAULT_MASK_CHAR.repeat(len);
        }
        StringBuilder sb = new StringBuilder(len);
        sb.append(input, 0, keepPrefix);
        sb.append(DEFAULT_MASK_CHAR.repeat(len - keepPrefix - keepSuffix));
        sb.append(input, len - keepSuffix, len);
        return sb.toString();
    }

    /**
     * 哈希策略：返回指定摘要算法的十六进制摘要。
     *
     * @param input     原始输入
     * @param params    算法名，默认 SHA-256
     */
    static String hash(String input, String params) {
        String algorithm = (params == null || params.isBlank()) ? "SHA-256" : params.trim();
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalArgumentException("hash algorithm not available: " + algorithm, e);
        }
    }

    /**
     * 替换策略：整体替换为指定字符串。
     *
     * @param input        原始输入（未使用，保留参数一致性）
     * @param params       替换字符串，默认 "***"
     */
    static String replace(String input, String params) {
        return (params == null || params.isBlank()) ? "***" : params;
    }

    /**
     * 假名化策略：生成与输入等长的随机小写字母字符串。
     * 保留数据长度形态，但不暴露原值，适合需要保持字段长度约束的场景。
     *
     * @param input 原始输入
     */
    static String pseudonymize(String input) {
        if (input == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            sb.append(PSEUDONYM_ALPHABET.charAt(RANDOM.nextInt(PSEUDONYM_ALPHABET.length())));
        }
        return sb.toString();
    }
}