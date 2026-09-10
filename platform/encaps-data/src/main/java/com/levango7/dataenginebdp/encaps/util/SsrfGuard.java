package com.levango7.dataenginebdp.encaps.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * SSRF（服务端请求伪造）防护工具。
 *
 * <p>在发起外部连接前校验目标地址，拒绝指向内网/环回/链路本地等敏感网段的请求，
 * 防止攻击者通过可控的 host 字段探测或访问内部服务。</p>
 *
 * <h3>拒绝的地址段</h3>
 * <ul>
 *   <li>IPv4 私有网络：10.0.0.0/8、172.16.0.0/12、192.168.0.0/16</li>
 *   <li>IPv4 环回：127.0.0.0/8</li>
 *   <li>IPv4 链路本地：169.254.0.0/16</li>
 *   <li>IPv4 本网络：0.0.0.0/8</li>
 *   <li>IPv6 环回：::1</li>
 *   <li>IPv6 私有（唯一本地地址）：fc00::/7</li>
 *   <li>IPv6 链路本地：fe80::/10</li>
 * </ul>
 *
 * <h3>DNS Rebinding 防护</h3>
 * <p>对域名输入，解析其所有 A/AAAA 记录，只要任一解析结果落入内网段即拒绝。
 * 避免攻击者使用首次解析返回公网 IP、后续解析返回内网 IP 的 DNS rebinding 攻击。</p>
 *
 * <h3>线程安全</h3>
 * <p>无共享可变状态，线程安全。可作 Spring 单例 Bean 或静态使用。</p>
 */
public final class SsrfGuard {

    /** 单例实例（无状态，可安全共享） */
    private static final SsrfGuard INSTANCE = new SsrfGuard();

    private SsrfGuard() {
    }

    /**
     * 获取单例实例。
     *
     * @return SsrfGuard 单例
     */
    public static SsrfGuard getInstance() {
        return INSTANCE;
    }

    /**
     * 校验目标主机地址是否允许连接。
     *
     * <p>对 IP 字面量直接检查网段；对域名解析所有 IP 后逐一检查。
     * 任一解析结果落入内网段则拒绝。</p>
     *
     * @param host 主机名或 IP 地址
     * @throws SsrfBlockedException 目标地址不允许连接
     * @throws IllegalArgumentException host 为 null 或空
     */
    public void validate(String host) {
        Objects.requireNonNull(host, "host 不可为 null");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host 不可为空");
        }

        // 先尝试作为 IP 字面量直接校验（避免 DNS 查询开销）
        byte[] rawBytes = parseIpLiteral(host);
        if (rawBytes != null) {
            if (isBlockedIp(rawBytes)) {
                throw new SsrfBlockedException(host, "目标 IP 地址位于禁止访问的网段");
            }
            return;
        }

        // 域名：解析所有 A/AAAA 记录，任一落入内网即拒绝（DNS rebinding 防护）
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new SsrfBlockedException(host, "无法解析主机名: " + e.getMessage());
        }

        for (InetAddress addr : addresses) {
            if (isBlockedIp(addr.getAddress())) {
                throw new SsrfBlockedException(host,
                        "主机名解析到禁止访问的内网地址 " + addr.getHostAddress());
            }
        }
    }

    /**
     * 校验目标主机和端口。
     *
     * @param host 主机名或 IP 地址
     * @param port 端口号
     * @throws SsrfBlockedException 目标地址不允许连接
     * @throws IllegalArgumentException host 为 null/空或端口越界
     */
    public void validate(String host, int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口越界: " + port + "，有效范围 1-65535");
        }
        validate(host);
    }

    /**
     * 判断 IP 地址是否落入禁止访问的网段。
     *
     * @param ipAddress IP 地址对象
     * @return true 表示应拒绝
     */
    public boolean isBlocked(InetAddress ipAddress) {
        Objects.requireNonNull(ipAddress, "ipAddress 不可为 null");
        return isBlockedIp(ipAddress.getAddress());
    }

    // ===== 内部实现 =====

    /**
     * 尝试将字符串解析为 IP 字面量（不触发 DNS 查询）。
     *
     * @param host 输入字符串
     * @return IP 的原始字节数组；非 IP 字面量返回 null
     */
    private static byte[] parseIpLiteral(String host) {
        // IPv4 字面量：包含且仅含数字和点
        if (host.chars().allMatch(c -> Character.isDigit(c) || c == '.')) {
            return parseIpv4Literal(host);
        }
        // IPv6 字面量：包含冒号
        if (host.contains(":")) {
            return parseIpv6Literal(host);
        }
        return null;
    }

    /**
     * 解析 IPv4 字面量。
     *
     * @param host IPv4 字符串
     * @return 4 字节数组；非法返回 null
     */
    private static byte[] parseIpv4Literal(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int octet = Integer.parseInt(parts[i]);
                if (octet < 0 || octet > 255) {
                    return null;
                }
                bytes[i] = (byte) octet;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return bytes;
    }

    /**
     * 解析 IPv6 字面量。
     *
     * @param host IPv6 字符串
     * @return 16 字节数组；非法返回 null
     */
    private static byte[] parseIpv6Literal(String host) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.getHostAddress().equals(host) || host.contains(":")) {
                byte[] bytes = addr.getAddress();
                if (bytes.length == 16) {
                    return bytes;
                }
            }
        } catch (UnknownHostException e) {
            return null;
        }
        return null;
    }

    /**
     * 判断 IP 原始字节是否落入禁止网段。
     *
     * @param bytes IP 原始字节（IPv4=4 字节，IPv6=16 字节）
     * @return true 表示应拒绝
     */
    private static boolean isBlockedIp(byte[] bytes) {
        if (bytes.length == 4) {
            return isBlockedIpv4(bytes);
        }
        if (bytes.length == 16) {
            return isBlockedIpv6(bytes);
        }
        // 未知长度，保守拒绝
        return true;
    }

    /**
     * IPv4 网段检查。
     *
     * <pre>
     * 10.0.0.0/8      → octet[0] == 10
     * 172.16.0.0/12   → octet[0] == 172 && (octet[1] & 0xF0) == 16
     * 192.168.0.0/16  → octet[0] == 192 && octet[1] == 168
     * 127.0.0.0/8     → octet[0] == 127
     * 169.254.0.0/16  → octet[0] == 169 && octet[1] == 254
     * 0.0.0.0/8       → octet[0] == 0
     * </pre>
     */
    private static boolean isBlockedIpv4(byte[] b) {
        int o0 = b[0] & 0xFF;
        int o1 = b[1] & 0xFF;

        // 0.0.0.0/8（本网络）
        if (o0 == 0) {
            return true;
        }
        // 10.0.0.0/8（私有 A 类）
        if (o0 == 10) {
            return true;
        }
        // 127.0.0.0/8（环回）
        if (o0 == 127) {
            return true;
        }
        // 169.254.0.0/16（链路本地）
        if (o0 == 169 && o1 == 254) {
            return true;
        }
        // 172.16.0.0/12（私有 B 类）
        if (o0 == 172 && (o1 & 0xF0) == 16) {
            return true;
        }
        // 192.168.0.0/16（私有 C 类）
        if (o0 == 192 && o1 == 168) {
            return true;
        }
        return false;
    }

    /**
     * IPv6 网段检查。
     *
     * <pre>
     * ::1            → 全 0 除最后一字节为 1
     * fc00::/7       → (b[0] & 0xFE) == 0xFC
     * fe80::/10      → (b[0] & 0xFF) == 0xFE && (b[1] & 0xC0) == 0x80
     * </pre>
     */
    private static boolean isBlockedIpv6(byte[] b) {
        // ::1（环回）
        boolean isLoopback = true;
        for (int i = 0; i < 15; i++) {
            if (b[i] != 0) {
                isLoopback = false;
                break;
            }
        }
        if (isLoopback && b[15] == 1) {
            return true;
        }

        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;

        // fc00::/7（唯一本地地址，IPv6 私有）
        if ((first & 0xFE) == 0xFC) {
            return true;
        }
        // fe80::/10（链路本地）
        if (first == 0xFE && (second & 0xC0) == 0x80) {
            return true;
        }
        return false;
    }
}