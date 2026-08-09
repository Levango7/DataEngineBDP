package com.levango7.dataenginebdp.function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.PostConstruct;

import java.util.HashMap;
import java.util.Map;

/**
 * 函数调用控制器 · 数擎大数据平台 T025.
 *
 * <p>提供函数 invocation REST 端点，封装为 Knative Service。</p>
 *
 * <p>端点：
 * <ul>
 *   <li>POST /invoke：函数调用入口</li>
 *   <li>GET /health：健康检查</li>
 * </ul></p>
 */
@RestController
@RequestMapping("/api/v1")
public class FunctionController {

    private final InvocationMetrics invocationMetrics;

    @Value("${function.name:default}")
    private String defaultFunctionName;

    @Value("${function.tenant-id:default-tenant}")
    private String defaultTenantId;

    /**
     * 构造函数.
     *
     * @param invocationMetrics invocation 计量组件
     */
    @Autowired
    public FunctionController(final InvocationMetrics invocationMetrics) {
        this.invocationMetrics = invocationMetrics;
    }

    /**
     * 启动后预热：初始化默认指标，降低首次请求延迟.
     */
    @PostConstruct
    public void warmup() {
        invocationMetrics.warmup(defaultTenantId, defaultFunctionName);
    }

    /**
     * 函数调用入口.
     *
     * @param tenantId   租户 ID 请求头
     * @param functionName 函数名请求头
     * @param event      调用事件
     * @return 调用响应
     */
    @PostMapping("/invoke")
    public ResponseEntity<Map<String, Object>> invoke(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default-tenant")
            final String tenantId,
            @RequestHeader(value = "X-Function-Name", defaultValue = "")
            final String functionNameHeader,
            @RequestBody(required = false) final Map<String, Object> event) {

        long startTime = System.nanoTime();
        String functionName = functionNameHeader.isEmpty()
                ? defaultFunctionName : functionNameHeader;
        Map<String, Object> inputEvent = event != null ? event : new HashMap<>();

        String status;
        int statusCode;
        Map<String, Object> result;

        try {
            // 调用内置 echo 函数（生产环境可动态加载用户函数）
            result = invokeFunction(functionName, inputEvent);
            status = "success";
            statusCode = HttpStatus.OK.value();
        } catch (Exception ex) {
            status = "error";
            statusCode = HttpStatus.INTERNAL_SERVER_ERROR.value();
            result = new HashMap<>();
            result.put("error", ex.getMessage());
            result.put("function", functionName);
        }

        long duration = System.nanoTime() - startTime;

        // invocation 计量
        invocationMetrics.record(tenantId, functionName, status, duration);

        return ResponseEntity.status(statusCode).body(result);
    }

    /**
     * 健康检查端点.
     *
     * @return 健康状态
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "UP");
        body.put("runtime", "java");
        body.put("function", defaultFunctionName);
        return ResponseEntity.ok(body);
    }

    /**
     * 内置 echo 函数（示例）.
     *
     * @param functionName 函数名
     * @param event        调用事件
     * @return 响应
     */
    private Map<String, Object> invokeFunction(final String functionName,
                                                final Map<String, Object> event) {
        Map<String, Object> result = new HashMap<>();
        result.put("runtime", "java");
        result.put("function", functionName);
        result.put("echo", event);
        result.put("message", "Hello from Java function runtime");
        return result;
    }
}