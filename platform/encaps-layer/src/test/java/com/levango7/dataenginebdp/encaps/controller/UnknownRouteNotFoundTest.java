package com.levango7.dataenginebdp.encaps.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 未知路径 404 语义验证（Sprint 2.2）。
 *
 * <p>背景：Spring Boot 4 下未匹配任何 handler 的路径会落入静态资源处理器抛
 * {@code NoResourceFoundException}，此前被 GlobalExceptionHandler 的兜底 handler
 * 误映射为 500（前端拿到误导性"内部错误"）。修复后应返回 404 + code=40401。</p>
 *
 * <p>用例选择带有效 JWT 的请求穿越安全过滤器（permitAll 路径不经过 JwtAuthFilter，
 * 无法覆盖完整链路；匿名请求会先被 401 拦截，测不到 404 分支）。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.security.jwt.secret=dev-secret-key-change-in-production-at-least-256-bits",
        "app.security.jwt.issuer=shuqing-bigdata",
        "app.security.oidc.enabled=false",
        "app.k8s.mock-enabled=true",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class UnknownRouteNotFoundTest {

    @Autowired
    private WebApplicationContext ctx;

    @Test
    @DisplayName("GET 不存在的路径应返回 404 + code=40401（而非 500）")
    void unknownRoute_returns404_not500() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(ctx)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        String token = io.jsonwebtoken.Jwts.builder()
                .subject("user-404-test")
                .claim("tenantId", "tenant-404")
                .issuer("shuqing-bigdata")
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 3600000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "dev-secret-key-change-in-production-at-least-256-bits"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(get("/api/v1/definitely-not-exist-route")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }
}
