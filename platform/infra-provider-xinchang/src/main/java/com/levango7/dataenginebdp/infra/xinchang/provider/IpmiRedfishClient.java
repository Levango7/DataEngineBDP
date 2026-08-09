package com.levango7.dataenginebdp.infra.xinchang.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.infra.xinchang.model.XinchangNodeSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * IPMI Redfish API 客户端。
 *
 * <p>通过 DMTF Redfish 标准接口（{@code /redfish/v1/Systems/{id}}）控制物理机：
 * 开机（On）、关机（ForceOff）、查询电源状态与启动顺序。BMC 厂商兼容鲲鹏 Mgmt / 海光 BMC / 飞腾 BMC。</p>
 *
 * <p>本实现使用 RestTemplate + Basic Auth，避免引入额外 SDK；超时由 {@link RestTemplateConfig} 统一配置。</p>
 */
@Component
public class IpmiRedfishClient {

    private static final Logger log = LoggerFactory.getLogger(IpmiRedfishClient.class);

    private static final String REDFISH_SYSTEMS_PATH = "/redfish/v1/Systems";
    private static final String POWER_ON = "On";
    private static final String POWER_FORCE_OFF = "ForceOff";
    private static final String BOOT_SOURCE_PXE = "Pxe";
    private static final String BOOT_MODE_ONCE = "Once";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String defaultUsername;
    private final String defaultPassword;

    /**
     * 构造客户端。
     *
     * @param restTemplate    已配置超时的 RestTemplate
     * @param objectMapper    JSON 序列化器
     * @param defaultUsername 默认 BMC 用户名（节点未指定时使用）
     * @param defaultPassword 默认 BMC 密码（节点未指定时使用）
     */
    public IpmiRedfishClient(RestTemplate restTemplate,
                             ObjectMapper objectMapper,
                             @Value("${app.xinchang.ipmi.username}") String defaultUsername,
                             @Value("${app.xinchang.ipmi.password}") String defaultPassword) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.defaultUsername = defaultUsername;
        this.defaultPassword = defaultPassword;
    }

    /**
     * 设置 PXE 一次性启动并开机。
     *
     * @param node 节点规格（含 BMC IP/MAC/凭据）
     */
    public void powerOnWithPxe(XinchangNodeSpec node) {
        String bmcUrl = "https://" + node.getBmcIp() + ":443";
        String systemPath = resolveSystemPath(bmcUrl, node);
        setBootSourcePxeOnce(bmcUrl, systemPath, node);
        powerAction(bmcUrl, systemPath, POWER_ON, node);
        log.info("IPMI PXE power-on issued for host={} bmc={}", node.getHostname(), node.getBmcIp());
    }

    /**
     * 强制关机。
     *
     * @param node 节点规格
     */
    public void powerOff(XinchangNodeSpec node) {
        String bmcUrl = "https://" + node.getBmcIp() + ":443";
        String systemPath = resolveSystemPath(bmcUrl, node);
        powerAction(bmcUrl, systemPath, POWER_FORCE_OFF, node);
        log.info("IPMI force-off issued for host={} bmc={}", node.getHostname(), node.getBmcIp());
    }

    /**
     * 查询电源状态。
     *
     * @param node 节点规格
     * @return 电源状态字符串（On/Off/Unknown）
     */
    public String getPowerState(XinchangNodeSpec node) {
        try {
            String bmcUrl = "https://" + node.getBmcIp() + ":443";
            String systemPath = resolveSystemPath(bmcUrl, node);
            HttpHeaders headers = buildAuthHeaders(node);
            ResponseEntity<String> resp = restTemplate.exchange(
                    bmcUrl + systemPath, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode powerState = root.path("PowerState");
            return powerState.asText("Unknown");
        } catch (Exception e) {
            log.warn("Failed to query power state for host={}: {}", node.getHostname(), e.getMessage());
            return "Unknown";
        }
    }

    private String resolveSystemPath(String bmcUrl, XinchangNodeSpec node) {
        // 默认取 Systems 集合的第一个成员（单系统 BMC 占绝大多数）
        try {
            HttpHeaders headers = buildAuthHeaders(node);
            ResponseEntity<String> resp = restTemplate.exchange(
                    bmcUrl + REDFISH_SYSTEMS_PATH, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode members = root.path("Members");
            if (members.isArray() && !members.isEmpty()) {
                String odataId = members.get(0).path("@odata.id").asText();
                if (!odataId.isEmpty()) {
                    return odataId;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve Systems path for bmc={}: {}", node.getBmcIp(), e.getMessage());
        }
        return REDFISH_SYSTEMS_PATH + "/1";
    }

    private void setBootSourcePxeOnce(String bmcUrl, String systemPath, XinchangNodeSpec node) {
        HttpHeaders headers = buildAuthHeaders(node);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("BootSourceOverrideTarget", BOOT_SOURCE_PXE);
        body.put("BootSourceOverrideEnabled", BOOT_MODE_ONCE);
        try {
            restTemplate.exchange(
                    bmcUrl + systemPath + "/Boot",
                    HttpMethod.PATCH,
                    new HttpEntity<>(body, headers),
                    String.class);
        } catch (Exception e) {
            log.warn("Failed to set PXE boot override for host={}: {}", node.getHostname(), e.getMessage());
        }
    }

    private void powerAction(String bmcUrl, String systemPath, String action, XinchangNodeSpec node) {
        HttpHeaders headers = buildAuthHeaders(node);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        Map<String, Object> reset = new HashMap<>();
        reset.put("Action", "Reset");
        body.put("ResetType", action);
        try {
            restTemplate.exchange(
                    bmcUrl + systemPath + "/Actions/ComputerSystem.Reset",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class);
        } catch (Exception e) {
            log.error("IPMI {} failed for host={} bmc={}: {}", action, node.getHostname(), node.getBmcIp(), e.getMessage());
            throw new IllegalStateException("IPMI action " + action + " failed for " + node.getHostname(), e);
        }
    }

    private HttpHeaders buildAuthHeaders(XinchangNodeSpec node) {
        String user = node.getBmcUsername() != null ? node.getBmcUsername() : defaultUsername;
        String pass = node.getBmcPassword() != null ? node.getBmcPassword() : defaultPassword;
        String auth = user + ":" + pass;
        String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + encoded);
        return headers;
    }
}