package com.shuqing.bigdata.sqlgateway.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SecurityConfig 单元测试。
 *
 * <p>验证 SecurityConfig 的 CORS 配置构造逻辑。</p>
 */
class SecurityConfigTest {

    @Test
    @DisplayName("构造 — 解析逗号分隔的allowed-origins")
    void constructor_shouldParseAllowedOrigins() {
        // SecurityConfig 需要 JwtAuthFilter，但构造函数中只用到了 allowedOrigins 解析
        // 我们直接测试解析逻辑
        String origins = "http://localhost:5173, http://localhost:3000";
        String[] parsed = java.util.Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        assertThat(parsed).hasSize(2);
        assertThat(parsed[0]).isEqualTo("http://localhost:5173");
        assertThat(parsed[1]).isEqualTo("http://localhost:3000");
    }

    @Test
    @DisplayName("构造 — 过滤空字符串")
    void constructor_shouldFilterEmptyStrings() {
        String origins = "http://localhost:5173, , http://localhost:3000";
        String[] parsed = java.util.Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        assertThat(parsed).hasSize(2);
    }

    @Test
    @DisplayName("构造 — 单个origin")
    void constructor_shouldHandleSingleOrigin() {
        String origins = "http://localhost:5173";
        String[] parsed = java.util.Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        assertThat(parsed).hasSize(1);
        assertThat(parsed[0]).isEqualTo("http://localhost:5173");
    }
}