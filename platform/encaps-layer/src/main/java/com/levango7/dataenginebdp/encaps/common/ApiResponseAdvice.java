package com.levango7.dataenginebdp.encaps.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * 响应体自动包装 Advice。
 *
 * <p>所有 Controller 返回的裸对象（如 {@code ResponseEntity.ok(tenant)} 解包后的 {@code tenant}）
 * 经本 Advice 自动包装为 {@link ApiResponse}，与前端 {@code client.ts} 拆包契约对齐。
 *
 * <p>排除以下场景（不包装）：
 * <ul>
 *   <li>body 已经是 {@link ApiResponse} 类型（避免二次包装，如 {@link GlobalExceptionHandler} 返回的）</li>
 *   <li>body 是 {@code String} 类型（避免 StringHttpMessageConverter 与 Jackson 序列化顺序冲突）</li>
 *   <li>body 为 {@code null}（直接返回 null，保持 204 No Content 语义）</li>
 *   <li>请求路径属于 Swagger / actuator / error 等基础设施端点</li>
 * </ul>
 *
 * <p>关键约束：不修改现有 Controller，仅通过 ResponseBodyAdvice 透明包装。
 */
@Slf4j
@RestControllerAdvice
public class ApiResponseAdvice implements org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice<Object> {

    /** 不进行包装的基础设施路径前缀 */
    private static final List<String> EXCLUDED_PATH_PREFIXES = List.of(
            "/v3/api-docs",
            "/swagger",
            "/actuator",
            "/error"
    );

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        // 对所有 converter 都生效，具体排除逻辑在 beforeBodyWrite 中处理
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        // 1. 排除基础设施端点
        String path = request.getURI().getPath();
        if (isExcludedPath(path)) {
            return body;
        }

        // 2. null 直接返回（保持 204 语义）
        if (body == null) {
            return null;
        }

        // 3. 已经是 ApiResponse，不再二次包装
        if (body instanceof ApiResponse<?>) {
            return body;
        }

        // 4. String 类型跳过：StringHttpMessageConverter 与 Jackson 处理顺序冲突，
        //    若强转包装会导致 ClassCastException（String converter 不走 Jackson）。
        //    如需对 String 接口包装，请 Controller 显式返回 ApiResponse.ok(str)。
        if (body instanceof String) {
            return body;
        }

        // 5. 自动包装为 ApiResponse.ok(body)
        return ApiResponse.ok(body);
    }

    /**
     * 判断请求路径是否属于排除范围（基础设施端点）。
     *
     * @param path 请求路径
     * @return true 表示排除，不进行包装
     */
    private boolean isExcludedPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        for (String prefix : EXCLUDED_PATH_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}