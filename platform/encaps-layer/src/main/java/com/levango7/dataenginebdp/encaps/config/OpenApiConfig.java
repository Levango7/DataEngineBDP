package com.levango7.dataenginebdp.encaps.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 规范配置（Springdoc）。
 *
 * <p>定义全局元信息（标题/版本/联系人/许可证）与 JWT Bearer 认证方案，
 * 供 {@code /v3/api-docs} 与 {@code /swagger-ui.html} 使用。</p>
 *
 * <p>端点：</p>
 * <ul>
 *   <li>{@code /v3/api-docs} — OpenAPI 3 JSON</li>
 *   <li>{@code /swagger-ui.html} — Swagger UI 页面</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "bearer-jwt";

    /**
     * OpenAPI 全局配置 Bean。
     *
     * @return OpenAPI 实例
     */
    @Bean
    public OpenAPI customOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("数据引擎大数据平台 - Encaps Layer API")
                        .description("Shuqing Big Data Platform 封装层 REST API 文档"
                                + "（认证、模板、标准、安全、ML、LLMOps、知识库、集成、开发、资产、安全门面）")
                        .version("0.1.0")
                        .contact(new Contact()
                                .name("DataEngineBDP Team")
                                .url("https://github.com/levango7/dataenginebdp"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(SECURITY_SCHEME_NAME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .in(SecurityScheme.In.HEADER)
                                        .description("JWT Bearer 认证（Authorization: Bearer <token>）")));
    }
}