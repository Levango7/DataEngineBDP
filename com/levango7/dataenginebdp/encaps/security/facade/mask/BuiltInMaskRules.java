package com.shuqing.bigdata.encaps.security.facade.mask;

import java.util.EnumMap;
import java.util.Map;

/**
 * 内置脱敏规则集合。
 *
 * <p>为 {@link MaskType} 中除 {@link #CUSTOM} 外的所有类型提供默认实现。
 * 所有规则均不依赖外部状态，可作为静态单例使用。</p>
 *
 * <h3>规则清单</h3>
 * <ul>
 *   <li>{@link PhoneMaskRule} — 138****5678</li>
 *   <li>{@link IdCardMaskRule} — 110101********1234</li>
 *   <li>{@link BankCardMaskRule} — 6222****1234</li>
 *   <li>{@link EmailMaskRule} — z***@example.com</li>
 *   <li>{@link NameMaskRule} — 张**</li>
 *   <li>{@link AddressMaskRule} — 北京市海淀区****</li>
 *   <li>{@link IpMaskRule} — 192.168.*.*</li>
 *   <li>{@link FullMaskRule} — *****</li>
 * </ul>
 */
public final class BuiltInMaskRules {

    private BuiltInMaskRules() {
    }

    /**
     * 创建包含所有内置规则的映射（按 MaskType 索引）。
     *
     * @return 不可变视图（调用方不应修改）
     */
    public static Map<MaskType, MaskRule> all() {
        Map<MaskType, MaskRule> map = new EnumMap<>(MaskType.class);
        map.put(MaskType.PHONE, new PhoneMaskRule());
        map.put(MaskType.ID_CARD, new IdCardMaskRule());
        map.put(MaskType.BANK_CARD, new BankCardMaskRule());
        map.put(MaskType.EMAIL, new EmailMaskRule());
        map.put(MaskType.NAME, new NameMaskRule());
        map.put(MaskType.ADDRESS, new AddressMaskRule());
        map.put(MaskType.IP, new IpMaskRule());
        map.put(MaskType.FULL, new FullMaskRule());
        return map;
    }

    // ===== 具体规则实现 =====

    /**
     * 手机号脱敏：保留前 3 后 4。
     */
    public static class PhoneMaskRule implements MaskRule {
        @Override
        public MaskType supportedType() { return MaskType.PHONE; }

        @Override
        public String mask(String input) {
            if (input == null || input.length() < 7) {
                return input;
            }
            return input.substring(0, 3) + repeat('*', input.length() - 7) + input.substring(input.length() - 4);
        }
    }

    /**
     * 身份证号脱敏：保留前 6 后 4。
     */
    public static class IdCardMaskRule implements MaskRule {
        @Override
        public MaskType supportedType() { return MaskType.ID_CARD; }

        @Override
        public String mask(String input) {
            if (input == null || input.length() < 10) {
                return input;
            }
            return input.substring(0, 6) + repeat('*', input.length() - 10) + input.substring(input.length() - 4);
        }
    }

    /**
     * 银行卡号脱敏：保留前 4 后 4。
     */
    public static class BankCardMaskRule implements MaskRule {
        @Override
        public MaskType supportedType() { return MaskType.BANK_CARD; }

        @Override
        public String mask(String input) {
            if (input == null || input.length() < 8) {
                return input;
            }
            return input.substring(0, 4) + repeat('*', input.length() - 8) + input.substring(input.length() - 4);
        }
    }

    /**
     * 邮箱脱敏：保留首字符与 @ 后域名。
     */
    public static class EmailMaskRule implements MaskRule {
        @Override
        public MaskType supportedType() { return MaskType.EMAIL; }

        @Override
        public String mask(String input) {
            if (input == null) {
                return null;
            }
            int at = input.indexOf('@');
            if (at <= 0) {
                return input;
            }
            String local = input.substring(0, at);
            String domain = input.substring(at);
            if (local.length() <= 1) {
                return local + domain;
            }
            return local.charAt(0) + repeat('*', local.length() - 1) + domain;
        }
    }

    /**
     * 姓名脱敏：保留姓氏，名脱敏。
     */
    public static class NameMaskRule implements MaskRule {
        @Override
        public MaskType supportedType() { return MaskType.NAME; }

        @Override
        public String mask(String input) {
            if (input == null || input.isEmpty()) {
                return input;
            }
            if (input.length() == 1) {
                return input;
            }
            return input.charAt(0) + repeat('*', input.length() - 1);
        }
    }

    /**
     * 地址脱敏：保留前 6 字符，其余脱敏。
     */
    public static class AddressMaskRule implements MaskRule {
        @Override
        public MaskType supportedType() { return MaskType.ADDRESS; }

        @Override
        public String mask(String input) {
            if (input == null || input.length() <= 6) {
                return input;
            }
            return input.substring(0, 6) + repeat('*', input.length() - 6);
        }
    }

    /**
     * IP 地址脱敏：保留前两段，后两段替换为 *。
     */
    public static class IpMaskRule implements MaskRule {
        @Override
        public MaskType supportedType() { return MaskType.IP; }

        @Override
        public String mask(String input) {
            if (input == null) {
                return null;
            }
            String[] parts = input.split("\\.", -1);
            if (parts.length != 4) {
                return input;
            }
            return parts[0] + "." + parts[1] + ".*.*";
        }
    }

    /**
     * 全脱敏：所有字符替换为 *。
     */
    public static class FullMaskRule implements MaskRule {
        @Override
        public MaskType supportedType() { return MaskType.FULL; }

        @Override
        public String mask(String input) {
            if (input == null) {
                return null;
            }
            return repeat('*', input.length());
        }
    }

    /**
     * 重复字符 n 次。
     *
     * @param c 字符
     * @param n 次数（非负）
     * @return 由 n 个 c 组成的字符串
     */
    static String repeat(char c, int n) {
        if (n <= 0) {
            return "";
        }
        char[] arr = new char[n];
        java.util.Arrays.fill(arr, c);
        return new String(arr);
    }
}