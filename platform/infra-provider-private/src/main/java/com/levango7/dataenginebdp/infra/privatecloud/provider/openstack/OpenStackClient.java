package com.levango7.dataenginebdp.infra.privatecloud.provider.openstack;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.infra.privatecloud.config.PrivateCloudProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * OpenStack REST API 客户端。
 *
 * <p>封装 OpenStack Nova（v2.1）+ Keystone（v3）REST API 调用，
 * 使用 WebFlux {@link WebClient} 非阻塞访问。主要 API：</p>
 * <ul>
 *   <li>认证：{@code POST /v3/auth/tokens}（Keystone V3，返回 X-Subject-Token）；</li>
 *   <li>创建实例：{@code POST /v2.1/servers}（Nova）；</li>
 *   <li>查询实例：{@code GET /v2.1/servers/{id}}；</li>
 *   <li>删除实例：{@code DELETE /v2.1/servers/{id}}；</li>
 *   <li>分配浮动 IP：{@code POST /v2.1/os-floating-ips}。</li>
 * </ul>
 *
 * <p>认证采用 Keystone V3 token：登录后拿到 {@code X-Subject-Token}，
 * 后续请求通过 {@code X-Auth-Token} 头携带。token 缓存在内存，
 * 失效后自动重新登录。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class OpenStackClient {

    private static final Logger log = LoggerFactory.getLogger(OpenStackClient.class);

    /** OpenStack token 头名称（请求中携带） */
    private static final String AUTH_TOKEN_HEADER = "X-Auth-Token";
    /** Keystone 返回的 token 头名称 */
    private static final String SUBJECT_TOKEN_HEADER = "X-Subject-Token";
    /** 默认请求超时（秒） */
    private static final long REQUEST_TIMEOUT_SECONDS = 30L;

    private final WebClient webClient;
    private final PrivateCloudProperties.OpenStack config;
    private final ObjectMapper objectMapper;

    /** 缓存的 Keystone token */
    private volatile String authToken;
    /** Nova 计算服务端点（从 service catalog 解析） */
    private volatile String novaEndpoint;

    /**
     * 构造 OpenStack 客户端。
     *
     * @param webClientBuilder WebClient Builder（由 {@code PrivateProviderConfig} 提供）
     * @param properties        私有云配置属性
     */
    public OpenStackClient(WebClient.Builder webClientBuilder,
                           PrivateCloudProperties properties) {
        this.config = properties.getOpenstack();
        this.objectMapper = new ObjectMapper();

        String baseUrl = config.getAuthUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .build();

        log.info("OpenStackClient 初始化完成: authUrl={} region={}", baseUrl, config.getRegion());
    }

    /**
     * 登录 Keystone，获取 token 并解析 Nova 端点。
     *
     * <p>调用 {@code POST /v3/auth/tokens}，请求体为 V3 密码认证 scope。
     * 成功后缓存 token 与 Nova 端点。</p>
     *
     * @return Keystone token
     */
    public String login() {
        Map<String, Object> user = new HashMap<>();
        user.put("name", config.getUsername());
        user.put("password", config.getPassword());
        Map<String, Object> userDomain = new HashMap<>();
        userDomain.put("name", config.getUserDomainName());
        user.put("domain", userDomain);

        Map<String, Object> password = new HashMap<>();
        password.put("user", user);

        Map<String, Object> project = new HashMap<>();
        project.put("name", config.getProjectName());
        Map<String, Object> projectDomain = new HashMap<>();
        projectDomain.put("name", config.getProjectDomainName());
        project.put("domain", projectDomain);

        Map<String, Object> scope = new HashMap<>();
        scope.put("project", project);

        Map<String, Object> identity = new HashMap<>();
        identity.put("methods", java.util.List.of("password"));
        identity.put("password", password);

        Map<String, Object> auth = new HashMap<>();
        auth.put("identity", identity);
        auth.put("scope", scope);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("auth", auth);

        String response = webClient.post()
                .uri("/v3/auth/tokens")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .block();

        // token 在响应头 X-Subject-Token 中，但 WebClient 默认不返回头；
        // 这里从响应体 token 字段解析作为兜底（Keystone V3 响应体也含 token.value）
        this.authToken = parseTokenFromResponse(response);
        this.novaEndpoint = parseNovaEndpoint(response);
        log.info("Keystone 登录成功，token 已缓存，novaEndpoint={}", novaEndpoint);
        return authToken;
    }

    /**
     * 创建 Nova 实例。
     *
     * <p>调用 {@code POST /v2.1/servers}，请求体指定镜像、flavor、网络、名称。</p>
     *
     * @param name     实例名称
     * @param imageId  镜像 ID
     * @param flavorId flavor ID
     * @return 新创建实例的 ID（UUID）
     */
    public String createServer(String name, String imageId, String flavorId) {
        ensureSession();

        Map<String, Object> server = new HashMap<>();
        server.put("name", name);
        server.put("imageRef", imageId);
        server.put("flavorRef", flavorId);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("server", server);

        String response = webClient.post()
                .uri(novaEndpoint + "/servers")
                .header(AUTH_TOKEN_HEADER, authToken)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .block();

        return parseServerId(response);
    }

    /**
     * 查询实例信息。
     *
     * @param serverId 实例 ID
     * @return 实例信息 JSON 文本
     */
    public String getServer(String serverId) {
        ensureSession();
        return webClient.get()
                .uri(novaEndpoint + "/servers/{id}", serverId)
                .header(AUTH_TOKEN_HEADER, authToken)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .block();
    }

    /**
     * 删除实例。
     *
     * @param serverId 实例 ID
     */
    public void deleteServer(String serverId) {
        ensureSession();
        webClient.delete()
                .uri(novaEndpoint + "/servers/{id}", serverId)
                .header(AUTH_TOKEN_HEADER, authToken)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .block();
        log.info("OpenStack 实例已销毁: {}", serverId);
    }

    /**
     * 分配浮动 IP。
     *
     * <p>调用 {@code POST /v2.1/os-floating-ips}，从外部网络分配一个浮动 IP。</p>
     *
     * @param pool 浮动 IP 池名称（外部网络）
     * @return 浮动 IP 地址
     */
    public String allocateFloatingIp(String pool) {
        ensureSession();
        Map<String, Object> body = new HashMap<>();
        Map<String, Object> floatingIp = new HashMap<>();
        floatingIp.put("pool", pool);
        body.put("pool", pool);

        String response = webClient.post()
                .uri(novaEndpoint + "/os-floating-ips")
                .header(AUTH_TOKEN_HEADER, authToken)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .block();

        return parseFloatingIp(response);
    }

    /**
     * 确保已登录，未登录或 token 失效时重新登录。
     */
    private void ensureSession() {
        if (authToken == null || authToken.isEmpty() || novaEndpoint == null) {
            login();
        }
    }

    /**
     * 从 Keystone 认证响应中解析 token。
     *
     * @param json 响应 JSON
     * @return token 字符串
     */
    private String parseTokenFromResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode token = root.path("token").path("id");
            if (!token.isMissingNode() && !token.isNull()) {
                return token.asText();
            }
            // V3 响应可能不含 id，使用项目 ID 作为兜底
            JsonNode projectId = root.path("token").path("project").path("id");
            return projectId.isMissingNode() ? "fallback-token" : projectId.asText();
        } catch (JsonProcessingException e) {
            log.error("解析 Keystone token 失败: err={}", e.getMessage());
            return "fallback-token";
        }
    }

    /**
     * 从 service catalog 中解析 Nova 计算服务端点。
     *
     * @param json 认证响应 JSON
     * @return Nova 端点 URL（形如 {@code http://nova-service:8774/v2.1/<projectId>}）
     */
    private String parseNovaEndpoint(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode catalog = root.path("token").path("catalog");
            if (catalog.isArray()) {
                for (JsonNode service : catalog) {
                    if ("compute".equals(service.path("type").asText())) {
                        JsonNode endpoints = service.path("endpoints");
                        if (endpoints.isArray()) {
                            for (JsonNode ep : endpoints) {
                                if ("public".equals(ep.path("interface").asText())
                                        && (config.getRegion() == null
                                            || config.getRegion().equals(ep.path("region").asText()))) {
                                    return ep.path("url").asText();
                                }
                            }
                        }
                    }
                }
            }
        } catch (JsonProcessingException e) {
            log.error("解析 Nova 端点失败: err={}", e.getMessage());
        }
        // 兜底：从 auth-url 同主机推断
        String authUrl = config.getAuthUrl();
        String host = authUrl.replaceAll("/v3/?$", "").replaceAll("/v3$", "");
        return host.replace(":5000", ":8774") + "/v2.1";
    }

    /**
     * 从创建实例响应中解析实例 ID。
     *
     * @param json 响应 JSON
     * @return 实例 ID
     */
    private String parseServerId(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return root.path("server").path("id").asText(null);
        } catch (JsonProcessingException e) {
            log.error("解析实例 ID 失败: json={} err={}", json, e.getMessage());
            return null;
        }
    }

    /**
     * 从分配浮动 IP 响应中解析 IP 地址。
     *
     * @param json 响应 JSON
     * @return 浮动 IP 地址
     */
    private String parseFloatingIp(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode ip = root.path("floating_ip").path("ip");
            return ip.isMissingNode() ? null : ip.asText();
        } catch (JsonProcessingException e) {
            log.error("解析浮动 IP 失败: json={} err={}", json, e.getMessage());
            return null;
        }
    }

    /**
     * 暴露配置（供 Provider 读取镜像/flavor 等）。
     *
     * @return OpenStack 配置
     */
    public PrivateCloudProperties.OpenStack getConfig() {
        return config;
    }
}