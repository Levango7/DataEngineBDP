package com.levango7.dataenginebdp.infra.privatecloud.provider.vsphere;

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
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * vSphere REST API 客户端。
 *
 * <p>封装 VMware vCenter 7.0+ REST API 调用，使用 WebFlux {@link WebClient}
 * 非阻塞访问。主要 API：</p>
 * <ul>
 *   <li>认证：{@code POST /rest/com/vmware/cis/session}（创建 session，返回 token）；</li>
 *   <li>克隆 VM：{@code POST /rest/vcenter/vm}（从模板克隆）；</li>
 *   <li>查询 VM：{@code GET /rest/vcenter/vm/{vm}}；</li>
 *   <li>电源操作：{@code POST /rest/vcenter/vm/{vm}/power/{action}}（start/stop/suspend）；</li>
 *   <li>销毁 VM：{@code DELETE /rest/vcenter/vm/{vm}}。</li>
 * </ul>
 *
 * <p>认证采用 session token：登录后拿到 {@code vmware-api-session-id}，
 * 后续请求通过 {@code vmware-api-session-id} 头携带。token 缓存在内存，
 * 失效后自动重新登录。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class VSphereClient {

    private static final Logger log = LoggerFactory.getLogger(VSphereClient.class);

    /** vCenter session 头名称 */
    private static final String SESSION_HEADER = "vmware-api-session-id";
    /** 默认请求超时（秒） */
    private static final long REQUEST_TIMEOUT_SECONDS = 30L;

    private final WebClient webClient;
    private final PrivateCloudProperties.VSphere config;
    private final ObjectMapper objectMapper;

    /** 缓存的 session token */
    private volatile String sessionToken;

    /**
     * 构造 vSphere 客户端。
     *
     * @param webClientBuilder WebClient Builder（由 {@code PrivateProviderConfig} 提供）
     * @param properties        私有云配置属性
     */
    public VSphereClient(WebClient.Builder webClientBuilder,
                         PrivateCloudProperties properties) {
        this.config = properties.getVsphere();
        this.objectMapper = new ObjectMapper();

        String baseUrl = config.getVcenterUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .build();

        log.info("VSphereClient 初始化完成: vcenterUrl={} username={}", baseUrl, config.getUsername());
    }

    /**
     * 登录 vCenter，获取 session token。
     *
     * <p>调用 {@code POST /rest/com/vmware/cis/session}，使用 Basic 认证。
     * 成功后缓存 session token 供后续请求使用。</p>
     *
     * @return session token
     */
    public String login() {
        String basicAuth = Base64.getEncoder().encodeToString(
                (config.getUsername() + ":" + config.getPassword()).getBytes());

        String token = webClient.post()
                .uri("/rest/com/vmware/cis/session")
                .header("Authorization", "Basic " + basicAuth)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .map(this::parseSessionValue)
                .block();

        this.sessionToken = token;
        log.info("vCenter 登录成功，session token 已缓存");
        return token;
    }

    /**
     * 从模板克隆并创建 VM。
     *
     * <p>调用 {@code POST /rest/vcenter/vm}，请求体指定 {@code source}（模板 VM）、
     * {@code placement}（数据中心/集群/资源池/文件夹）、{@code name}。</p>
     *
     * @param vmName    新 VM 名称
     * @param template  模板 VM 名称
     * @return 新创建 VM 的 ID（形如 {@code vm-xxxx}）
     */
    public String cloneVm(String vmName, String template) {
        ensureSession();

        Map<String, Object> placement = new HashMap<>();
        placement.put("folder", config.getFolder());
        placement.put("datastore", config.getDatastore());
        placement.put("cluster", config.getCluster());

        Map<String, Object> source = new HashMap<>();
        source.put("name", template);

        Map<String, Object> spec = new HashMap<>();
        spec.put("name", vmName);
        spec.put("source", source);
        spec.put("placement", placement);

        String response = webClient.post()
                .uri("/rest/vcenter/vm")
                .header(SESSION_HEADER, sessionToken)
                .bodyValue(spec)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .block();

        return parseStringValue(response, "value");
    }

    /**
     * 启动 VM。
     *
     * @param vmId VM ID
     */
    public void powerOn(String vmId) {
        ensureSession();
        webClient.post()
                .uri("/rest/vcenter/vm/{vm}/power/start", vmId)
                .header(SESSION_HEADER, sessionToken)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .block();
        log.info("VM 已开机: {}", vmId);
    }

    /**
     * 关闭 VM 电源。
     *
     * @param vmId VM ID
     */
    public void powerOff(String vmId) {
        ensureSession();
        webClient.post()
                .uri("/rest/vcenter/vm/{vm}/power/stop", vmId)
                .header(SESSION_HEADER, sessionToken)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .block();
        log.info("VM 已关机: {}", vmId);
    }

    /**
     * 查询 VM 信息。
     *
     * @param vmId VM ID
     * @return VM 信息 JSON 文本
     */
    public String getVm(String vmId) {
        ensureSession();
        return webClient.get()
                .uri("/rest/vcenter/vm/{vm}", vmId)
                .header(SESSION_HEADER, sessionToken)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .block();
    }

    /**
     * 销毁 VM。
     *
     * <p>先关机再删除，调用 {@code DELETE /rest/vcenter/vm/{vm}}。</p>
     *
     * @param vmId VM ID
     */
    public void deleteVm(String vmId) {
        ensureSession();
        try {
            powerOff(vmId);
        } catch (Exception e) {
            log.warn("关机失败，继续删除 VM: {} err={}", vmId, e.getMessage());
        }
        webClient.delete()
                .uri("/rest/vcenter/vm/{vm}", vmId)
                .header(SESSION_HEADER, sessionToken)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .block();
        log.info("VM 已销毁: {}", vmId);
    }

    /**
     * 确保已登录，未登录或 token 失效时重新登录。
     */
    private void ensureSession() {
        if (sessionToken == null || sessionToken.isEmpty()) {
            login();
        }
    }

    /**
     * 从 JSON 响应中解析 {@code value} 字段的字符串值。
     *
     * <p>vCenter REST API 响应结构通常为 {@code {"value":"xxx"}}。</p>
     *
     * @param json JSON 文本
     * @param field 字段名
     * @return 字符串值
     */
    private String parseStringValue(String json, String field) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode node = root.get(field);
            return node == null ? null : node.asText();
        } catch (JsonProcessingException e) {
            log.error("解析 vSphere 响应失败: json={} err={}", json, e.getMessage());
            return null;
        }
    }

    /**
     * 解析 session 响应。
     *
     * <p>vCenter 返回 {@code {"value":"session-id-xxx"}}。</p>
     *
     * @param json 响应 JSON
     * @return session token
     */
    private String parseSessionValue(String json) {
        return parseStringValue(json, "value");
    }

    /**
     * 暴露配置（供 Provider 读取模板/文件夹等）。
     *
     * @return vSphere 配置
     */
    public PrivateCloudProperties.VSphere getConfig() {
        return config;
    }
}