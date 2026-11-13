package com.levango7.dataenginebdp.flinkcdc.materializedview;

import com.levango7.dataenginebdp.common.security.JwtAuthFilter;
import com.levango7.dataenginebdp.common.security.ratelimit.RateLimitFilter;
import com.levango7.dataenginebdp.flinkcdc.materializedview.config.MaterializedViewConfig;
import com.levango7.dataenginebdp.flinkcdc.materializedview.service.MaterializedViewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * 物化视图管理面宿主（实时链路补齐专项 Phase C1，台账 #119① 的 C1 选项）。
 *
 * <p>flink-cdc 模块此前只有「作业提交器」形态（{@code CdcFrameworkMain} + CLI 镜像），
 * {@code MaterializedViewController} 声明的 7 条 {@code /api/materialized-views} 路由
 * 无进程承载、APISIX 生成的 {@code flink-cdc:8080} 路由悬空。本类补一个**最小 Spring Boot 入口**
 * ——只承载物化视图管理面（建/查/改/删/刷/状态 + 探针），<b>不启动 CDC 作业</b>
 * （作业提交仍走 CliFrontend/提交器镜像，互不影响；两种形态共用同一 jar、以主类区分）。</p>
 *
 * <p><b>安全面</b>：本类自定义 {@link SecurityFilterChain}（镜像 common-security
 * {@code SecurityConfig} 的过滤链形状）；健康端点取白名单口径 {@code /api/v1/health}
 * （common-security {@code JwtAuthFilter} 的放行清单之一，见
 * {@code ManagementHealthController} 注释）；其余 {@code /api/**} 仍要求 JWT
 * （由 common-security 的 {@code JwtAuthFilter} 自动装配提供，配置键
 * {@code app.security.jwt.secret/issuer}）。
 * common-security 的 {@code SecurityConfig} 因 {@code @ConditionalOnMissingBean(SecurityFilterChain)}
 * 自动退让，共享模块零改动。</p>
 *
 * <p>启动命令：{@code java -cp flink-cdc.jar com.levango7.dataenginebdp.flinkcdc.materializedview.MaterializedViewManagementApplication}
 * （chart 侧经 {@code command/args} 值项切换，见 design/deploy/charts/flink-cdc/values.yaml）。</p>
 */
@SpringBootApplication(scanBasePackages = "com.levango7.dataenginebdp.flinkcdc.materializedview")
public class MaterializedViewManagementApplication {

    private static final Logger log = LoggerFactory.getLogger(MaterializedViewManagementApplication.class);

    /**
     * 入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(MaterializedViewManagementApplication.class, args);
    }

    /**
     * SQL 执行器 Bean（刷新 SQL → Doris FE HTTP）。
     *
     * @param config 物化视图配置
     * @return ViewRefresher 约定的 SQL 执行器
     */
    @Bean
    public Function<String, Boolean> materializedViewSqlExecutor(MaterializedViewConfig config) {
        return new DorisHttpSqlExecutor(config);
    }

    /**
     * 生命周期：应用就绪后启动刷新触发器，关闭时停止。
     *
     * @param service 物化视图服务
     * @return 生命周期 Bean
     */
    @Bean
    public MaterializedViewLifecycle materializedViewLifecycle(MaterializedViewService service) {
        return new MaterializedViewLifecycle(service);
    }

    /**
     * 安全过滤链：探针面放行 + API 走 JWT。
     *
     * @param http         HttpSecurity
     * @param jwtAuthFilter JWT 过滤器（common-security 自动装配提供）
     * @param rateLimitFilter 速率限制过滤器（common-security 自动装配提供）
     * @param mvCorsConfigurationSource CORS 配置源
     * @return 过滤链
     * @throws Exception 构建失败
     */
    @Bean
    public SecurityFilterChain materializedViewSecurityFilterChain(HttpSecurity http,
                                                                   JwtAuthFilter jwtAuthFilter,
                                                                   RateLimitFilter rateLimitFilter,
                                                                   CorsConfigurationSource mvCorsConfigurationSource)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(mvCorsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 健康端点（平台白名单口径）与容器错误派发放行
                        .requestMatchers("/api/v1/health").permitAll()
                        .requestMatchers("/error").permitAll()
                        // 其余（含 /api/materialized-views 全族）要求认证
                        .anyRequest().authenticated())
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * CORS 配置源（与 common-security 同口径；允许源为空时不放行任何跨域）。
     *
     * @param allowedOrigins 逗号分隔允许源
     * @return CORS 配置源
     */
    @Bean
    public CorsConfigurationSource mvCorsConfigurationSource(
            @Value("${app.security.cors.allowed-origins:}") String allowedOrigins) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(origins));
        configuration.setAllowedMethods(Arrays.asList(HttpMethod.GET.name(), HttpMethod.POST.name(),
                HttpMethod.PUT.name(), HttpMethod.DELETE.name(), HttpMethod.PATCH.name(), HttpMethod.OPTIONS.name()));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Requested-With", "X-Tenant-Id"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /** 启动/停止刷新触发器的生命周期适配。 */
    static final class MaterializedViewLifecycle implements ApplicationRunner, DisposableBean {

        private final MaterializedViewService service;

        MaterializedViewLifecycle(MaterializedViewService service) {
            this.service = service;
        }

        @Override
        public void run(ApplicationArguments args) {
            service.start();
            log.info("物化视图管理面就绪（刷新触发器已启动）");
        }

        @Override
        public void destroy() {
            service.stop();
        }
    }
}
