package com.levango7.dataenginebdp.federated.scheduling;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Karmada PropagationPolicy YAML 生成器。
 *
 * <p>根据 {@link SchedulingPolicy} 生成 Karmada PropagationPolicy YAML 字符串，
 * 不依赖 Kubernetes Java Client，纯字符串拼接，避免引入新依赖。
 *
 * <p>支持生成的字段：
 * <ul>
 *   <li>resourceSelectors：关联的工作负载（apiVersion/kind/name）</li>
 *   <li>placement.clusterAffinity：集群亲和性标签选择</li>
 *   <li>placement.clusterTolerations：集群污点容忍</li>
 *   <li>placement.replicaScheduling：副本调度策略（Duplicated / Spread / Aggregated）</li>
 *   <li>placement.placementPolicyType：放置策略类型</li>
 *   <li>placement.topologySpreadConstraints：拓扑分布约束</li>
 * </ul>
 *
 * <p>生成示例：
 * <pre>
 * apiVersion: policy.karmada.io/v1alpha1
 * kind: PropagationPolicy
 * metadata:
 *   name: my-policy
 *   namespace: default
 * spec:
 *   resourceSelectors:
 *     - apiVersion: apps/v1
 *       kind: Deployment
 *       name: my-app
 *   placement:
 *     clusterAffinity:
 *       labelSelector:
 *         matchLabels:
 *           env: prod
 *     replicaScheduling:
 *       replicaSchedulingType: Divided
 *       replicaDivisionPreference: Weighted
 * </pre>
 */
@Slf4j
@Component
public class PropagationPolicyGenerator {

    /** Karmada PropagationPolicy API 版本。 */
    public static final String API_VERSION = "policy.karmada.io/v1alpha1";

    /** Karmada PropagationPolicy Kind。 */
    public static final String KIND = "PropagationPolicy";

    /**
     * 根据 {@link SchedulingPolicy} 生成 PropagationPolicy YAML 字符串。
     *
     * @param policy 调度策略
     * @return PropagationPolicy YAML 字符串
     */
    public String generate(SchedulingPolicy policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        log.debug("Generating PropagationPolicy for policy: {}", policy.getName());

        StringBuilder yaml = new StringBuilder();
        appendHeader(yaml, policy);
        appendMetadata(yaml, policy);
        yaml.append("spec:\n");
        appendResourceSelectors(yaml, policy);
        appendPlacement(yaml, policy);
        appendAnnotations(yaml, policy);

        String result = yaml.toString();
        log.debug("Generated PropagationPolicy YAML ({} chars) for policy: {}", result.length(), policy.getName());
        return result;
    }

    /**
     * 批量生成 PropagationPolicy YAML，多个策略以 `---` 分隔。
     *
     * @param policies 调度策略列表
     * @return 多文档 YAML 字符串
     */
    public String generateAll(List<SchedulingPolicy> policies) {
        Objects.requireNonNull(policies, "policies must not be null");
        return policies.stream()
                .map(this::generate)
                .collect(Collectors.joining("\n---\n", "", "\n"));
    }

    private void appendHeader(StringBuilder yaml, SchedulingPolicy policy) {
        yaml.append("apiVersion: ").append(API_VERSION).append('\n');
        yaml.append("kind: ").append(KIND).append('\n');
    }

    private void appendMetadata(StringBuilder yaml, SchedulingPolicy policy) {
        yaml.append("metadata:\n");
        yaml.append("  name: ").append(safe(policy.getName())).append('\n');
        yaml.append("  namespace: ").append(safe(policy.getNamespace())).append('\n');
        yaml.append("  labels:\n");
        yaml.append("    app.kubernetes.io/managed-by: federated-scheduler\n");
        if (policy.getPolicyTypes() != null && !policy.getPolicyTypes().isEmpty()) {
            yaml.append("    scheduling.policy/types: ")
                    .append(policy.getPolicyTypes().stream()
                            .map(Enum::name)
                            .collect(Collectors.joining(",")))
                    .append('\n');
        }
    }

    private void appendResourceSelectors(StringBuilder yaml, SchedulingPolicy policy) {
        yaml.append("  resourceSelectors:\n");
        yaml.append("    - apiVersion: ").append(safe(policy.getApiVersion())).append('\n');
        yaml.append("      kind: ").append(safe(policy.getKind())).append('\n');
        if (policy.getWorkloadName() != null && !policy.getWorkloadName().isBlank()) {
            yaml.append("      name: ").append(policy.getWorkloadName()).append('\n');
        }
    }

    private void appendPlacement(StringBuilder yaml, SchedulingPolicy policy) {
        yaml.append("  placement:\n");
        appendClusterAffinity(yaml, policy);
        appendClusterTolerations(yaml, policy);
        appendReplicaScheduling(yaml, policy);
        appendPlacementPolicyType(yaml, policy);
        appendTopologySpreadConstraints(yaml, policy);
    }

    private void appendClusterAffinity(StringBuilder yaml, SchedulingPolicy policy) {
        Map<String, String> affinity = policy.getClusterAffinity();
        if (affinity == null || affinity.isEmpty()) {
            return;
        }
        yaml.append("    clusterAffinity:\n");
        yaml.append("      labelSelector:\n");
        yaml.append("        matchLabels:\n");
        affinity.forEach((k, v) -> yaml.append("          ").append(k).append(": ").append(quote(v)).append('\n'));
    }

    private void appendClusterTolerations(StringBuilder yaml, SchedulingPolicy policy) {
        List<String> tolerations = policy.getClusterTolerations();
        if (tolerations == null || tolerations.isEmpty()) {
            return;
        }
        yaml.append("    clusterTolerations:\n");
        for (String tol : tolerations) {
            String[] parts = tol.split("[:=]", 3);
            yaml.append("      - key: ").append(parts.length > 0 ? parts[0] : "").append('\n');
            yaml.append("        operator: Equal\n");
            if (parts.length >= 2) {
                yaml.append("        value: ").append(parts[1]).append('\n');
            }
            yaml.append("        effect: ").append(parts.length >= 3 ? parts[2] : "NoSchedule").append('\n');
        }
    }

    private void appendReplicaScheduling(StringBuilder yaml, SchedulingPolicy policy) {
        SchedulingPolicy.ReplicaDistribution dist = policy.getReplicaDistribution();
        if (dist == null) {
            return;
        }
        yaml.append("    replicaScheduling:\n");
        switch (dist) {
            case DUPLICATED -> {
                yaml.append("      replicaSchedulingType: Duplicated\n");
            }
            case SPREAD -> {
                yaml.append("      replicaSchedulingType: Divided\n");
                yaml.append("      replicaDivisionPreference: Weighted\n");
                yaml.append("      weightPreference:\n");
                yaml.append("        staticWeightList:\n");
                yaml.append("          - targetCluster:\n");
                yaml.append("              clusterNames:\n");
                yaml.append("                - \"*\"\n");
                yaml.append("            weight: ").append(Math.max(1, policy.getWeight())).append('\n');
            }
            case AGGREGATED -> {
                yaml.append("      replicaSchedulingType: Divided\n");
                yaml.append("      replicaDivisionPreference: Aggregated\n");
            }
            default -> log.warn("Unknown replica distribution: {}", dist);
        }
    }

    private void appendPlacementPolicyType(StringBuilder yaml, SchedulingPolicy policy) {
        SchedulingPolicy.ReplicaDistribution dist = policy.getReplicaDistribution();
        if (dist == null) {
            return;
        }
        // Karmada placementPolicyType: Duplicated / Spread / Aggregated
        yaml.append("    placementPolicyType: ").append(dist.name()).append('\n');
    }

    private void appendTopologySpreadConstraints(StringBuilder yaml, SchedulingPolicy policy) {
        if (policy.getPolicyTypes() == null
                || !policy.getPolicyTypes().contains(SchedulingPolicy.PolicyType.TOPOLOGY_AWARE)) {
            return;
        }
        SchedulingPolicy.TopologyDomain domain = policy.getTopologyDomain();
        if (domain == null) {
            return;
        }
        yaml.append("    topologySpreadConstraints:\n");
        yaml.append("      - maxSkew: 1\n");
        yaml.append("        topologyKey: ").append(domain.name().toLowerCase()).append('\n');
        yaml.append("        whenUnsatisfiable: DoNotSchedule\n");
        yaml.append("        minDomains: ").append(Math.max(1, policy.getMinTopologySpread())).append('\n');
    }

    private void appendAnnotations(StringBuilder yaml, SchedulingPolicy policy) {
        Map<String, String> ext = policy.getExtensions();
        if (ext == null || ext.isEmpty()) {
            return;
        }
        // 已在 metadata 中追加 labels，此处追加 annotations
        yaml.insert(yaml.indexOf("spec:"), buildAnnotationsBlock(ext));
    }

    private String buildAnnotationsBlock(Map<String, String> ext) {
        StringBuilder sb = new StringBuilder();
        sb.append("  annotations:\n");
        ext.forEach((k, v) -> sb.append("    ").append(k).append(": ").append(quote(v)).append('\n'));
        return sb.toString();
    }

    /**
     * 从 PropagationPolicy YAML 中提取 policy name（用于校验和日志）。
     *
     * @param yaml PropagationPolicy YAML
     * @return policy name，提取失败返回 null
     */
    public String extractPolicyName(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return null;
        }
        for (String line : yaml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("name:") && !trimmed.startsWith("namespace:")) {
                return trimmed.substring("name:".length()).trim();
            }
        }
        return null;
    }

    /**
     * 校验 PropagationPolicy YAML 是否包含必需字段。
     *
     * @param yaml PropagationPolicy YAML
     * @return 缺失字段列表，空列表表示校验通过
     */
    public List<String> validate(String yaml) {
        List<String> missing = new ArrayList<>();
        if (yaml == null || yaml.isBlank()) {
            missing.add("yaml is blank");
            return missing;
        }
        if (!yaml.contains("apiVersion: " + API_VERSION)) {
            missing.add("apiVersion");
        }
        if (!yaml.contains("kind: " + KIND)) {
            missing.add("kind");
        }
        if (!yaml.contains("metadata:")) {
            missing.add("metadata");
        }
        if (!yaml.contains("spec:")) {
            missing.add("spec");
        }
        if (!yaml.contains("resourceSelectors:")) {
            missing.add("resourceSelectors");
        }
        if (!yaml.contains("placement:")) {
            missing.add("placement");
        }
        return missing;
    }

    /** 收集所有候选集群名（从 clusterAffinity 推断，主要用于测试辅助）。 */
    public List<String> collectCandidateClusters(SchedulingPolicy policy) {
        Map<String, String> affinity = policy.getClusterAffinity();
        if (affinity == null) {
            return new ArrayList<>();
        }
        // affinity 是标签选择器，无法直接得到集群名，这里返回标签签名用于测试
        List<String> signatures = new ArrayList<>();
        affinity.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> signatures.add(e.getKey() + "=" + e.getValue()));
        return signatures;
    }

    /** 转换为有序 map（用于测试断言 YAML 结构）。 */
    public Map<String, Object> toStructuredMap(SchedulingPolicy policy) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("apiVersion", API_VERSION);
        root.put("kind", KIND);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", policy.getName());
        metadata.put("namespace", policy.getNamespace());
        root.put("metadata", metadata);
        Map<String, Object> spec = new LinkedHashMap<>();
        List<Map<String, String>> selectors = new ArrayList<>();
        Map<String, String> selector = new LinkedHashMap<>();
        selector.put("apiVersion", policy.getApiVersion());
        selector.put("kind", policy.getKind());
        if (policy.getWorkloadName() != null) {
            selector.put("name", policy.getWorkloadName());
        }
        selectors.add(selector);
        spec.put("resourceSelectors", selectors);
        spec.put("placement", new LinkedHashMap<>());
        root.put("spec", spec);
        return root;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        // 包含特殊字符时加引号
        if (s.contains(":") || s.contains("#") || s.contains("{") || s.contains("}")
                || s.contains("[") || s.contains("]") || s.contains(",")
                || s.contains("&") || s.contains("*") || s.contains("?")
                || s.contains("|") || s.contains(">") || s.contains("@")
                || s.contains("`") || s.contains("\"") || s.contains("'")) {
            return "\"" + s.replace("\"", "\\\"") + "\"";
        }
        return s;
    }
}