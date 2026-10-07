package com.levango7.dataenginebdp.encaps.workspace;

import java.util.regex.Pattern;

/**
 * 租户业务键在 K8s 侧的形态校验。
 *
 * <p>本服务的租户标识是字符串业务键（台账 #56 方案③）。它会被写进 Namespace 名、
 * NetworkPolicy 的 {@code tenantId} 标签值与 RoleBinding 组名，而这三处各有字符集约束
 * （小写字母/数字/连字符、长度 ≤63）。校验放在这里，是为了让"租户键不合形态"在
 * 本地就抛清晰异常，而不是等 API Server 拒掉一个半成品对象后留下难以归因的错误。</p>
 */
final class K8sTenantKeys {

    /** K8s label 值与 DNS-1123 label 的交集形态，长度上限取更严的 63。 */
    private static final Pattern SAFE = Pattern.compile("^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$");

    private K8sTenantKeys() {
    }

    /**
     * @param tenantId 租户业务键（实体字段为字符串；参数留 Object 以兼容历史 {@code String.valueOf} 调用点）
     * @return 可安全用于 K8s 名称与标签值的租户键
     * @throws IllegalArgumentException 形态不合法（大写、下划线、中文、空白或超长）
     */
    static String requireSafe(Object tenantId) {
        String value = tenantId == null ? "" : String.valueOf(tenantId);
        if (!SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "租户业务键 \"" + value + "\" 不是合法的 K8s 名称/标签值：只允许小写字母、数字与连字符，"
                            + "首尾须为字母或数字，长度 ≤63");
        }
        return value;
    }
}
