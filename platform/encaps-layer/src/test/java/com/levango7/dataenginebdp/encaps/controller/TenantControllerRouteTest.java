package com.levango7.dataenginebdp.encaps.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户路由守卫（@ConditionalOnProperty）验证测试。
 *
 * <p>背景（Sprint 2.1）：平台内存在两个类级前缀均为 {@code /api/v1/tenants} 的
 * 控制器——encaps-layer 与 encaps-tenant（不同微服务、不同端口、不同 JVM，
 * 当前架构下不会同 context 加载）。为防御未来可能出现的模块聚合 / 单体化部署，
 * encaps-layer 的 TenantController 增加了 {@code app.tenant.controller.enabled}
 * 开关（默认启用）：
 * <ul>
 *   <li>默认（matchIfMissing=true）：{@code /api/v1/tenants} 系列 handler 应已注册；</li>
 *   <li>显式关闭（app.tenant.controller.enabled=false）：{@code /api/v1/tenants} 系列
 *       handler 不应注册。</li>
 * </ul>
 * 本测试用 {@link RequestMappingHandlerMapping} 直接断言 handler 注册情况，绕开
 * MockMvc 走完整 servlet dispatch 路径（后者在无 handler 时 Spring Boot 4 会抛
 * NoResourceFoundException 进入全局异常处理，不便于断言路由缺失）。
 *
 * <p>注入注意：actuator 也会注册一个 RequestMappingHandlerMapping（bean 名
 * controllerEndpointHandlerMapping），故必须用 @Qualifier 指定 MVC 的
 * requestMappingHandlerMapping。</p>
 */
class TenantControllerRouteTest {

    /** 提取 HandlerMapping 中所有以 /api/v1/tenants 开头的注册路径。 */
    private Set<String> tenantRoutes(RequestMappingHandlerMapping mapping) {
        return mapping.getHandlerMethods().keySet().stream()
                .map(RequestMappingInfo::getPathPatternsCondition)
                .filter(cond -> cond != null)
                .flatMap(cond -> cond.getPatternValues().stream())
                .filter(p -> p.startsWith("/api/v1/tenants"))
                .collect(Collectors.toSet());
    }

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {
            "app.security.jwt.secret=dev-secret-key-change-in-production-at-least-256-bits",
            "app.security.jwt.issuer=shuqing-bigdata",
            "app.security.oidc.enabled=false",
            "app.k8s.mock-enabled=true",
            "spring.jpa.hibernate.ddl-auto=create-drop"
    })
    @DisplayName("默认启用（app.tenant.controller.enabled 未显式设置 → matchIfMissing=true）")
    class EnabledByDefault {
        @Autowired
        @Qualifier("requestMappingHandlerMapping")
        private RequestMappingHandlerMapping mapping;

        @Test
        @DisplayName("/api/v1/tenants 系列 handler 应在 HandlerMapping 中")
        void tenantHandlers_registered_whenEnabledByDefault() {
            Set<String> routes = tenantRoutes(mapping);
            assertThat(routes).contains("/api/v1/tenants", "/api/v1/tenants/{id}");
        }
    }

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {
            "app.security.jwt.secret=dev-secret-key-change-in-production-at-least-256-bits",
            "app.security.jwt.issuer=shuqing-bigdata",
            "app.security.oidc.enabled=false",
            "app.k8s.mock-enabled=true",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "app.tenant.controller.enabled=false"
    })
    @DisplayName("显式关闭（app.tenant.controller.enabled=false → 路由不注册）")
    class DisabledByProperty {
        @Autowired
        @Qualifier("requestMappingHandlerMapping")
        private RequestMappingHandlerMapping mapping;

        @Test
        @DisplayName("/api/v1/tenants 系列 handler 不应在 HandlerMapping 中")
        void tenantHandlers_absent_whenDisabled() {
            Set<String> routes = tenantRoutes(mapping);
            assertThat(routes).doesNotContain("/api/v1/tenants", "/api/v1/tenants/{id}");
        }
    }
}
