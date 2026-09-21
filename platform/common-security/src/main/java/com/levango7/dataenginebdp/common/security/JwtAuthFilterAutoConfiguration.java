package com.levango7.dataenginebdp.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.nio.charset.StandardCharsets;

/**
 * 默认 {@link JwtAuthFilter} Bean 的独立自动装配（与过滤链自动配置解耦）。
 *
 * <p><b>为什么需要独立成类（修复 P0：类级退让粒度太粗）</b>：
 * {@link JwtAuthFilter} 的默认实现原本定义在 {@link SecurityConfig} 中，而
 * {@link SecurityConfig} 被类级 {@code @ConditionalOnMissingBean(SecurityFilterChain.class)}
 * 守卫——业务模块一旦自定义过滤链（如 master-data / data-standard 的 API Key + Bearer 双通道
 * 链），整个 {@link SecurityConfig} 类退让，默认的 {@link JwtAuthFilter} 也随之消失，
 * 但这些模块的过滤链恰恰又要注入它，结果是
 * {@code No qualifying bean of type 'JwtAuthFilter'}、应用无法启动。</p>
 *
 * <p>本类把「默认 Bean 工厂」与「过滤链」这两件本应独立的事拆开：无论业务模块是否自定义
 * 过滤链，只要它自己没有提供 {@link JwtAuthFilter}，本类就提供默认实现。</p>
 *
 * <p><b>三条安全护栏</b>：</p>
 * <ol>
 *   <li>{@code @ConditionalOnMissingBean(JwtAuthFilter.class)}（类型）+
 *       {@code name = "jwtAuthFilter"}（Bean 名）：自建实现的模块（如 encaps-layer 通过
 *       组件扫描注册的 {@code encaps.security.JwtAuthFilter}）优先，本类自动退让。
 *       两个维度都要判：encaps-layer 的实现是独立类型（不继承本类），仅靠类型判断不会退让，
 *       而它的组件扫描 Bean 名恰好也是 {@code jwtAuthFilter}，仅靠类型判断会撞
 *       {@code BeanDefinitionOverrideException}。</li>
 *   <li>Bean 方法命名为 {@code defaultJwtAuthFilter}（而非 {@code jwtAuthFilter}）：即使
 *       上述退让被绕过，也不会与模块自建 Bean 同名而触发 Bean 定义覆盖冲突。</li>
 *   <li>{@link JwtConfiguredCondition}：仅当 {@code app.security.jwt.secret} 与
 *       {@code app.security.jwt.issuer} 均已配置、可解析且密钥满足 HMAC-SHA 最低长度
 *       （32 字节）时才创建 Bean。本类不再随过滤链退让，意味着<b>所有</b>引入
 *       common-security 的模块都会走到这里——若不加此守卫，未配置 JWT 的模块会从
 *       「以前没事」变成「启动失败」。这是本次改动最大的回归风险点。</li>
 * </ol>
 *
 * <p><b>为什么不注册为 servlet 过滤器</b>：Spring Boot 会自动把 {@code Filter} 类型的 Bean
 * 注册到 servlet 容器（默认 {@code /*}）。对「过滤链里已经装配了本过滤器」的模块，容器级
 * 注册只是被 {@code OncePerRequestFilter} 去重掉的空跑；但对「自定义过滤链且不走 JWT」的
 * 模块（lineage-analyzer / federated-query 的 oauth2ResourceServer 链），容器级注册会让本
 * 过滤器成为唯一生效实例，把没有 Bearer 头的请求全部 401——属于行为变更。因此统一用
 * {@code enabled=false} 的 {@link FilterRegistrationBean} 关闭容器级自动注册：过滤器只通过
 * 模块自己的 {@code SecurityFilterChain} 生效，不隐式接管任何请求。</p>
 */
@AutoConfiguration
@ConditionalOnMissingBean(value = JwtAuthFilter.class, name = "jwtAuthFilter")
@Conditional(JwtAuthFilterAutoConfiguration.JwtConfiguredCondition.class)
public class JwtAuthFilterAutoConfiguration {

    /** JWT 签名密钥配置项 */
    static final String SECRET_PROPERTY = "app.security.jwt.secret";

    /** JWT issuer 配置项 */
    static final String ISSUER_PROPERTY = "app.security.jwt.issuer";

    /** HMAC-SHA 签名密钥最低字节数（256 bit） */
    static final int MIN_SECRET_BYTES = 32;

    /**
     * 默认 JWT 认证过滤器 Bean：当容器中不存在 {@link JwtAuthFilter} 类型时提供通用实现。
     *
     * <p>需要特化扩展（如 SM2 国密、OIDC）的模块应自行声明 {@link JwtAuthFilter} Bean，
     * 本方法将自动退让。</p>
     *
     * @param secret JWT 签名密钥
     * @param issuer JWT issuer
     * @return 通用 JwtAuthFilter 实例
     */
    @Bean
    public JwtAuthFilter defaultJwtAuthFilter(@Value("${" + SECRET_PROPERTY + "}") String secret,
                                              @Value("${" + ISSUER_PROPERTY + "}") String issuer) {
        return new JwtAuthFilter(secret, issuer);
    }

    /**
     * 关闭 Spring Boot 对 {@link JwtAuthFilter} 的容器级自动注册。
     *
     * <p>过滤器只应通过模块自己的 {@code SecurityFilterChain} 生效（由模块的 SecurityConfig
     * 显式 {@code addFilterBefore}），不能被 Boot 隐式注册到 {@code /*} 上——那会让不走
     * JWT 的模块（如 oauth2ResourceServer 链）被本过滤器接管并返回 401。</p>
     *
     * @param jwtAuthFilter 上一步创建的过滤器
     * @return 禁用状态的注册 Bean
     */
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> defaultJwtAuthFilterRegistration(JwtAuthFilter defaultJwtAuthFilter) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(defaultJwtAuthFilter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * JWT 配置存在性守卫。
     *
     * <p>判定顺序：secret 已配置且可解析 → secret 满足 HMAC 最低长度 → issuer 已配置且可解析。
     * 任一不满足即不创建 Bean（避免因"本类不再随过滤链退让"而让未配置 JWT 的模块启动失败）。</p>
     *
     * <p>注意：模块配置里形如 {@code secret: ${JWT_SECRET}} 的写法在环境变量缺失时，
     * {@link Environment#getProperty} 会抛占位符未解析异常——这等价于"未配置"，必须吞掉而不是
     * 让它冒泡成启动失败。</p>
     */
    public static final class JwtConfiguredCondition extends SpringBootCondition {

        private static final Logger log = LoggerFactory.getLogger(JwtConfiguredCondition.class);

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment environment = context.getEnvironment();

            String secret = resolve(environment, SECRET_PROPERTY);
            if (secret == null || secret.isBlank()) {
                return noMatch(SECRET_PROPERTY + " 未配置或占位符无法解析");
            }
            if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
                return noMatch(SECRET_PROPERTY + " 长度不足 " + MIN_SECRET_BYTES + " 字节（HMAC-SHA 最低要求）");
            }
            String issuer = resolve(environment, ISSUER_PROPERTY);
            if (issuer == null || issuer.isBlank()) {
                return noMatch(ISSUER_PROPERTY + " 未配置或占位符无法解析");
            }
            return ConditionOutcome.match(ConditionMessage.forCondition("JwtAuthFilter")
                    .foundExactly(SECRET_PROPERTY + " / " + ISSUER_PROPERTY));
        }

        private ConditionOutcome noMatch(String reason) {
            log.warn("未装配默认 JwtAuthFilter Bean：{}", reason);
            return ConditionOutcome.noMatch(ConditionMessage.forCondition("JwtAuthFilter").because(reason));
        }

        /**
         * 读取配置；占位符无法解析时返回 {@code null}（等价于未配置）。
         */
        private String resolve(Environment environment, String key) {
            try {
                return environment.getProperty(key);
            } catch (RuntimeException ex) {
                return null;
            }
        }
    }
}
