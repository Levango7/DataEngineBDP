package com.levango7.dataenginebdp.encaps.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SsrfGuard} 单元测试。
 *
 * <p>覆盖各内网 IP 段拦截、公网 IP 放行、IPv6 拦截、端口校验等场景。</p>
 */
@DisplayName("SSRF 防护测试")
class SsrfGuardTest {

    private final SsrfGuard guard = SsrfGuard.getInstance();

    // ===== IPv4 内网段拦截 =====

    @Nested
    @DisplayName("IPv4 内网段拦截")
    class Ipv4Blocked {

        @Test
        @DisplayName("10.0.0.0/8 私有网络 A 类被拦截")
        void block_10_x_x_x() {
            assertThatThrownBy(() -> guard.validate("10.0.0.1"))
                    .isInstanceOf(SsrfBlockedException.class);
            assertThatThrownBy(() -> guard.validate("10.255.255.255"))
                    .isInstanceOf(SsrfBlockedException.class);
            assertThatThrownBy(() -> guard.validate("10.1.2.3"))
                    .isInstanceOf(SsrfBlockedException.class);
        }

        @Test
        @DisplayName("172.16.0.0/12 私有网络 B 类被拦截")
        void block_172_16_x_x() {
            assertThatThrownBy(() -> guard.validate("172.16.0.1"))
                    .isInstanceOf(SsrfBlockedException.class);
            assertThatThrownBy(() -> guard.validate("172.31.255.255"))
                    .isInstanceOf(SsrfBlockedException.class);
            assertThatThrownBy(() -> guard.validate("172.20.30.40"))
                    .isInstanceOf(SsrfBlockedException.class);
        }

        @Test
        @DisplayName("172.16.0.0/12 边界：172.15 和 172.32 不拦截")
        void block_172_16_boundary() {
            // 172.15.x.x 不在 172.16.0.0/12 范围内
            assertThatCode(() -> guard.validate("172.15.0.1")).doesNotThrowAnyException();
            // 172.32.x.x 不在范围内
            assertThatCode(() -> guard.validate("172.32.0.1")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("192.168.0.0/16 私有网络 C 类被拦截")
        void block_192_168_x_x() {
            assertThatThrownBy(() -> guard.validate("192.168.0.1"))
                    .isInstanceOf(SsrfBlockedException.class);
            assertThatThrownBy(() -> guard.validate("192.168.1.100"))
                    .isInstanceOf(SsrfBlockedException.class);
            assertThatThrownBy(() -> guard.validate("192.168.255.255"))
                    .isInstanceOf(SsrfBlockedException.class);
        }

        @Test
        @DisplayName("127.0.0.0/8 环回地址被拦截")
        void block_127_x_x_x() {
            assertThatThrownBy(() -> guard.validate("127.0.0.1"))
                    .isInstanceOf(SsrfBlockedException.class);
            assertThatThrownBy(() -> guard.validate("127.1.2.3"))
                    .isInstanceOf(SsrfBlockedException.class);
            assertThatThrownBy(() -> guard.validate("127.255.255.255"))
                    .isInstanceOf(SsrfBlockedException.class);
        }

        @Test
        @DisplayName("169.254.0.0/16 链路本地被拦截")
        void block_169_254_x_x() {
            assertThatThrownBy(() -> guard.validate("169.254.0.1"))
                    .isInstanceOf(SsrfBlockedException.class);
            assertThatThrownBy(() -> guard.validate("169.254.169.254"))
                    .isInstanceOf(SsrfBlockedException.class);
        }

        @Test
        @DisplayName("0.0.0.0/8 本网络被拦截")
        void block_0_x_x_x() {
            assertThatThrownBy(() -> guard.validate("0.0.0.0"))
                    .isInstanceOf(SsrfBlockedException.class);
            assertThatThrownBy(() -> guard.validate("0.1.2.3"))
                    .isInstanceOf(SsrfBlockedException.class);
        }
    }

    // ===== IPv6 拦截 =====

    @Nested
    @DisplayName("IPv6 内网段拦截")
    class Ipv6Blocked {

        @Test
        @DisplayName("::1 环回地址被拦截")
        void block_ipv6_loopback() {
            assertThatThrownBy(() -> guard.validate("::1"))
                    .isInstanceOf(SsrfBlockedException.class);
        }

        @Test
        @DisplayName("fc00::/7 唯一本地地址被拦截")
        void block_ipv6_ula() {
            assertThatThrownBy(() -> guard.validate("fc00::1"))
                    .isInstanceOf(SsrfBlockedException.class);
            assertThatThrownBy(() -> guard.validate("fd00::1"))
                    .isInstanceOf(SsrfBlockedException.class);
            assertThatThrownBy(() -> guard.validate("fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"))
                    .isInstanceOf(SsrfBlockedException.class);
        }

        @Test
        @DisplayName("fe80::/10 链路本地被拦截")
        void block_ipv6_linkLocal() {
            assertThatThrownBy(() -> guard.validate("fe80::1"))
                    .isInstanceOf(SsrfBlockedException.class);
        }
    }

    // ===== 公网 IP 放行 =====

    @Nested
    @DisplayName("公网 IP 放行")
    class Ipv4Allowed {

        @Test
        @DisplayName("8.8.8.8 公网 DNS 放行")
        void allow_8_8_8_8() {
            assertThatCode(() -> guard.validate("8.8.8.8")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("1.1.1.1 公网 DNS 放行")
        void allow_1_1_1_1() {
            assertThatCode(() -> guard.validate("1.1.1.1")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("172.15.0.1 公网放行（不在 172.16/12 内）")
        void allow_172_15() {
            assertThatCode(() -> guard.validate("172.15.0.1")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("172.32.0.1 公网放行（不在 172.16/12 内）")
        void allow_172_32() {
            assertThatCode(() -> guard.validate("172.32.0.1")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("11.0.0.1 公网放行（不在 10/8 内）")
        void allow_11() {
            assertThatCode(() -> guard.validate("11.0.0.1")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("193.0.0.1 公网放行（不在 192.168/16 内）")
        void allow_193() {
            assertThatCode(() -> guard.validate("193.0.0.1")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("公网 IPv6 放行")
        void allow_ipv6_public() {
            // 2001:4860:4860::8888 是 Google DNS 的 IPv6
            assertThatCode(() -> guard.validate("2001:4860:4860::8888")).doesNotThrowAnyException();
        }
    }

    // ===== 端口校验 =====

    @Nested
    @DisplayName("端口校验")
    class PortValidation {

        @Test
        @DisplayName("有效端口放行")
        void validPort() {
            assertThatCode(() -> guard.validate("8.8.8.8", 53)).doesNotThrowAnyException();
            assertThatCode(() -> guard.validate("8.8.8.8", 1)).doesNotThrowAnyException();
            assertThatCode(() -> guard.validate("8.8.8.8", 65535)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("端口 0 抛异常")
        void portZero() {
            assertThatThrownBy(() -> guard.validate("8.8.8.8", 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("负端口抛异常")
        void portNegative() {
            assertThatThrownBy(() -> guard.validate("8.8.8.8", -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("端口超过 65535 抛异常")
        void portTooLarge() {
            assertThatThrownBy(() -> guard.validate("8.8.8.8", 65536))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ===== 输入校验 =====

    @Nested
    @DisplayName("输入校验")
    class InputValidation {

        @Test
        @DisplayName("null host 抛 NullPointerException")
        void nullHost() {
            assertThatThrownBy(() -> guard.validate(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("空 host 抛 IllegalArgumentException")
        void emptyHost() {
            assertThatThrownBy(() -> guard.validate(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("空白 host 抛 IllegalArgumentException")
        void blankHost() {
            assertThatThrownBy(() -> guard.validate("   "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ===== 异常属性 =====

    @Test
    @DisplayName("拦截异常包含被拦截的主机信息")
    void exceptionContainsHost() {
        assertThatThrownBy(() -> guard.validate("10.0.0.1"))
                .isInstanceOfSatisfying(SsrfBlockedException.class, ex -> {
                    assertThat(ex.getHost()).isEqualTo("10.0.0.1");
                    assertThat(ex.getMessage()).isNotNull();
                });
    }
}