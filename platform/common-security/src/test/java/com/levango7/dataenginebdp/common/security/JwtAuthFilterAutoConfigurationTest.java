package com.levango7.dataenginebdp.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtAuthFilterAutoConfiguration 的退让与守卫行为测试。
 *
 * <p>本自动配置类不再随 {@code SecurityFilterChain} 退让，意味着<b>所有</b>引入
 * common-security 的模块都会尝试创建默认 JwtAuthFilter。因此必须证明：</p>
 * <ul>
 *   <li>JWT 配置齐全时创建默认 Bean，且容器级自动注册被关闭（不隐式接管请求）</li>
 *   <li>缺少 secret / issuer、占位符无法解析、密钥过短时<b>不创建</b> Bean 且上下文正常启动
 *       ——这是"未配置 JWT 的模块不会被炸掉"的回归护栏</li>
 *   <li>模块自带 JwtAuthFilter 时本配置自动退让，模块自己的实现优先</li>
 * </ul>
 */
@DisplayName("JwtAuthFilterAutoConfiguration 守卫与退让")
class JwtAuthFilterAutoConfigurationTest {

    /** 满足 HMAC-SHA 256 bit 要求的测试密钥 */
    private static final String VALID_SECRET =
            "test-secret-key-for-hmac-sha-256-at-least-32-bytes-long";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JwtAuthFilterAutoConfiguration.class));

    @Test
    @DisplayName("配置齐全时创建默认 JwtAuthFilter")
    void shouldCreateDefaultFilterWhenJwtConfigured() {
        contextRunner
                .withPropertyValues("app.security.jwt.secret=" + VALID_SECRET,
                        "app.security.jwt.issuer=test-issuer")
                .run(context -> {
                    assertThat(context).hasSingleBean(JwtAuthFilter.class);
                    assertThat(context.getBean(JwtAuthFilter.class)).isNotNull();
                });
    }

    @Test
    @DisplayName("默认 JwtAuthFilter 关闭容器级自动注册，避免隐式接管所有请求")
    void shouldDisableServletContainerRegistration() {
        contextRunner
                .withPropertyValues("app.security.jwt.secret=" + VALID_SECRET,
                        "app.security.jwt.issuer=test-issuer")
                .run(context -> {
                    assertThat(context).hasSingleBean(FilterRegistrationBean.class);
                    FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
                    assertThat(registration.isEnabled()).isFalse();
                    assertThat(registration.getFilter()).isSameAs(context.getBean(JwtAuthFilter.class));
                });
    }

    @Test
    @DisplayName("未配置 app.security.jwt.secret 时不创建 Bean，且上下文正常启动")
    void shouldNotCreateFilterWhenSecretMissing() {
        contextRunner
                .withPropertyValues("app.security.jwt.issuer=test-issuer")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(JwtAuthFilter.class);
                });
    }

    @Test
    @DisplayName("secret 为无法解析的占位符（如 ${JWT_SECRET} 未注入）时不创建 Bean，且上下文正常启动")
    void shouldNotCreateFilterWhenSecretPlaceholderUnresolvable() {
        contextRunner
                .withPropertyValues("app.security.jwt.secret=${JWT_SECRET_NOT_SET_FOR_TEST}",
                        "app.security.jwt.issuer=test-issuer")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(JwtAuthFilter.class);
                });
    }

    @Test
    @DisplayName("secret 长度不足 32 字节时不创建 Bean（避免 HMAC 弱密钥异常炸掉启动）")
    void shouldNotCreateFilterWhenSecretTooShort() {
        contextRunner
                .withPropertyValues("app.security.jwt.secret=too-short",
                        "app.security.jwt.issuer=test-issuer")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(JwtAuthFilter.class);
                });
    }

    @Test
    @DisplayName("未配置 app.security.jwt.issuer 时不创建 Bean，且上下文正常启动")
    void shouldNotCreateFilterWhenIssuerMissing() {
        contextRunner
                .withPropertyValues("app.security.jwt.secret=" + VALID_SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(JwtAuthFilter.class);
                });
    }

    @Test
    @DisplayName("模块自带 JwtAuthFilter 时本配置退让，模块实现优先")
    void shouldBackOffWhenModuleProvidesOwnFilter() {
        contextRunner
                .withPropertyValues("app.security.jwt.secret=" + VALID_SECRET,
                        "app.security.jwt.issuer=test-issuer")
                .withUserConfiguration(CustomJwtAuthFilterConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(JwtAuthFilter.class);
                    assertThat(context.getBean(JwtAuthFilter.class))
                            .isSameAs(CustomJwtAuthFilterConfiguration.CUSTOM_FILTER);
                });
    }

    @Test
    @DisplayName("模块自带同名 jwtAuthFilter Bean（独立类型，如 encaps-layer）时本配置退让，不发生 Bean 定义覆盖冲突")
    void shouldBackOffWhenModuleProvidesBeanNamedJwtAuthFilter() {
        contextRunner
                .withPropertyValues("app.security.jwt.secret=" + VALID_SECRET,
                        "app.security.jwt.issuer=test-issuer")
                .withUserConfiguration(UnrelatedJwtAuthFilterConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(JwtAuthFilter.class);
                    assertThat(context).hasSingleBean(UnrelatedJwtAuthFilter.class);
                });
    }

    /** 模拟业务模块自带的 JwtAuthFilter 实现（如 encaps-layer 的国密/OIDC 特化版）。 */
    @Configuration(proxyBeanMethods = false)
    static class CustomJwtAuthFilterConfiguration {

        static final JwtAuthFilter CUSTOM_FILTER =
                new JwtAuthFilter(VALID_SECRET, "custom-issuer");

        @Bean
        public JwtAuthFilter customJwtAuthFilter() {
            return CUSTOM_FILTER;
        }
    }

    /**
     * 模拟 encaps-layer 场景：模块通过组件扫描注册了一个<b>独立类型</b>的 JWT 过滤器，
     * 且 Bean 名恰好也是 {@code jwtAuthFilter}（由类名派生）。
     */
    @Configuration(proxyBeanMethods = false)
    static class UnrelatedJwtAuthFilterConfiguration {

        @Bean
        public UnrelatedJwtAuthFilter jwtAuthFilter() {
            return new UnrelatedJwtAuthFilter();
        }
    }

    /** 与 common-security 的 JwtAuthFilter 无继承关系的独立过滤器实现。 */
    static class UnrelatedJwtAuthFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                                        jakarta.servlet.http.HttpServletResponse response,
                                        jakarta.servlet.FilterChain filterChain) {
            // 测试替身：不做任何处理
        }
    }
}
