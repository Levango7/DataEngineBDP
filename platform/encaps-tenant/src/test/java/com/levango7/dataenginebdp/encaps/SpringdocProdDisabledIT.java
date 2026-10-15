package com.levango7.dataenginebdp.encaps;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI/Swagger 端点生产暴露面的回归守卫（2026-10-16 安全审计发现）。
 *
 * <p><b>背景</b>：本服务组件扫描白名单包含 encaps-layer 的 {@code ...encaps.security} 包
 * （等保审计切面所在），用的正是 layer 侧 {@code SecurityConfig} —— 其对
 * {@code /swagger-ui/**} 与 {@code /v3/api-docs/**} 一律 permitAll，源码注释写
 * "生产环境可通过 profile 关闭"，但全仓 prod profile 从未实现该开关
 * ⇒ 两个端点在生产<b>匿名可达</b>（API 结构元数据暴露）。</p>
 *
 * <p><b>为什么需要本类</b>：这正是"声称已关、实际没关"的治理问题形态——只靠注释与
 * 口头约定没有约束力，必须有测试把约定钉住。两类断言分别防两种回归：</p>
 * <ul>
 *   <li>{@link #prodProfileExplicitlyDisablesApiDocs()}：prod profile 里显式关闭两个
 *       springdoc 开关（防配置被后人删掉，注释承诺重新变成空话）；</li>
 *   <li>{@link #apiDocsAndSwaggerUiNotAnonymouslyServed()}：开关置关后端点不得再被
 *       匿名服务（4xx；不是 200）——防有人把 SecurityConfig 的 permitAll 又放开。</li>
 * </ul>
 *
 * <p>注意：光给 {@code OpenApiConfig} 加 {@code @ConditionalOnProperty} 不够 ——
 * springdoc 自动配置自身仍会注册 {@code /v3/api-docs}，必须同时用 springdoc 的
 * 原生开关 {@code springdoc.api-docs.enabled} / {@code springdoc.swagger-ui.enabled}
 * 把端点本身关掉。本测试就是对这件事的行为化证明。</p>
 */
@SpringBootTest(
        classes = EncapsTenantApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "springdoc.api-docs.enabled=false",
                "springdoc.swagger-ui.enabled=false"
        }
)
class SpringdocProdDisabledIT {

    @Autowired
    private WebApplicationContext wac;

    /**
     * prod profile 必须显式关闭两个 springdoc 端点开关。
     * 读的是本模块 src/main/resources/application-prod.yml（surefire 的 CWD = 模块 basedir）。
     */
    @Test
    void prodProfileExplicitlyDisablesApiDocs() throws Exception {
        Map<String, Object> prod;
        try (InputStream in = new java.io.FileInputStream("src/main/resources/application-prod.yml")) {
            prod = new Yaml().load(in);
        }

        Map<?, ?> springdoc = (Map<?, ?>) prod.get("springdoc");
        assertThat(springdoc)
                .as("application-prod.yml 必须含 springdoc 段（2026-10-16 暴露面收口）")
                .isNotNull();

        assertThat(Boolean.FALSE.equals(((Map<?, ?>) springdoc.get("api-docs")).get("enabled")))
                .as("prod 必须 springdoc.api-docs.enabled=false")
                .isTrue();
        assertThat(Boolean.FALSE.equals(((Map<?, ?>) springdoc.get("swagger-ui")).get("enabled")))
                .as("prod 必须 springdoc.swagger-ui.enabled=false")
                .isTrue();
    }

    /**
     * 开关置关后，/v3/api-docs 与 /swagger-ui.html 不得被匿名服务。
     * 端点不存在时 Spring Security 的 anyRequest().authenticated() 会要求认证
     * （401/403）或落到 404 —— 二者都满足"不匿名"，但 200 一定不行。
     */
    @Test
    void apiDocsAndSwaggerUiNotAnonymouslyServed() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is4xxClientError());
    }
}
