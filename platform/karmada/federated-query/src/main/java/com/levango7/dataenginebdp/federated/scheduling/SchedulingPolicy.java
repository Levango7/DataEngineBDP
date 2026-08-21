package com.levango7.dataenginebdp.federated.scheduling;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Karmada 联邦调度策略模型。
 *
 * <p>描述工作负载在多集群间的传播与调度约束，包含：
 * <ul>
 *   <li>策略类型：亲和性 / 反亲和性 / 拓扑感知</li>
 *   <li>集群标签选择器：限定候选集群范围</li>
 *   <li>拓扑域：region / zone / az，用于拓扑感知调度</li>
 *   <li>权重：多策略组合时的优先级权重</li>
 *   <li>传播偏好：副本分布策略（Duplicated / Spread / Aggregated）</li>
 * </ul>
 *
 * <p>该模型由 {@link PropagationPolicyGenerator} 转换为 Karmada PropagationPolicy YAML，
 * 由 {@link FederatedScheduler} 在调度决策时使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchedulingPolicy implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 策略名称（唯一标识，对应 PropagationPolicy.metadata.name）。 */
    private String name;

    /** 命名空间（默认 default）。 */
    @Builder.Default
    private String namespace = "default";

    /** 关联工作负载的 API 版本（如 apps/v1）。 */
    @Builder.Default
    private String apiVersion = "apps/v1";

    /** 关联工作负载的 Kind（如 Deployment / StatefulSet）。 */
    @Builder.Default
    private String kind = "Deployment";

    /** 关联工作负载名称（空表示匹配该 namespace 下所有该 Kind）。 */
    private String workloadName;

    /** 策略类型列表，可组合（如同时启用 AFFINITY 与 TOPOLOGY_AWARE）。 */
    @Builder.Default
    private List<PolicyType> policyTypes = new ArrayList<>();

    /** 集群标签选择器：键值对，候选集群必须匹配所有键值对。 */
    @Builder.Default
    private Map<String, String> clusterAffinity = new HashMap<>();

    /** 集群反亲和性标签：候选集群不能匹配这些键值对。 */
    @Builder.Default
    private Map<String, String> clusterAntiAffinity = new HashMap<>();

    /** 集群污点容忍列表，每项格式为 "key=value:Effect"。 */
    @Builder.Default
    private List<String> clusterTolerations = new ArrayList<>();

    /** 拓扑域类型：region / zone / az（默认 zone）。 */
    @Builder.Default
    private TopologyDomain topologyDomain = TopologyDomain.ZONE;

    /** 跨拓扑域分布的最小集群数（默认 2）。 */
    @Builder.Default
    private int minTopologySpread = 2;

    /** 是否启用跨 region 分布（最高优先级）。 */
    @Builder.Default
    private boolean crossRegionSpread = true;

    /** 是否启用跨 zone 分布（次优先级）。 */
    @Builder.Default
    private boolean crossZoneSpread = true;

    /** 是否启用同 zone 内聚集（最低优先级，节省跨 zone 带宽）。 */
    @Builder.Default
    private boolean sameZoneAggregation = false;

    /** 副本分布策略：Duplicated / Spread / Aggregated。 */
    @Builder.Default
    private ReplicaDistribution replicaDistribution = ReplicaDistribution.SPREAD;

    /** 副本总数（Duplicated 模式下每集群副本数，Spread/Aggregated 模式下总副本数）。 */
    @Builder.Default
    private int replicas = 1;

    /** 调度权重（多策略组合时使用，范围 1-100，默认 50）。 */
    @Builder.Default
    private int weight = 50;

    /** 是否优先调度到低延迟集群（latency-first profile）。 */
    @Builder.Default
    private boolean latencyFirst = false;

    /** 是否优先调度到低成本集群（cost-first profile）。 */
    @Builder.Default
    private boolean costFirst = false;

    /** 是否优先调度到高可用集群（availability-first profile）。 */
    @Builder.Default
    private boolean availabilityFirst = false;

    /** 自定义扩展参数（透传到 PropagationPolicy annotations）。 */
    @Builder.Default
    private Map<String, String> extensions = new HashMap<>();

    /**
     * 调度策略类型枚举。
     */
    public enum PolicyType {
        /** 亲和性：优先调度到匹配 clusterAffinity 标签的集群。 */
        AFFINITY,
        /** 反亲和性：避免调度到匹配 clusterAntiAffinity 标签的集群。 */
        ANTI_AFFINITY,
        /** 拓扑感知：根据拓扑域（region/zone/az）跨域分布。 */
        TOPOLOGY_AWARE
    }

    /**
     * 拓扑域类型枚举。
     */
    public enum TopologyDomain {
        /** 跨 region 分布（地理级容灾）。 */
        REGION,
        /** 跨 zone 分布（数据中心级容灾）。 */
        ZONE,
        /** 跨 az 分布（可用区级容灾）。 */
        AZ
    }

    /**
     * 副本分布策略枚举。
     */
    public enum ReplicaDistribution {
        /** 复制：每个候选集群都部署完整副本（适合无状态服务的多集群高可用）。 */
        DUPLICATED,
        /** 分散：副本均匀分散到多个集群（适合分片/负载均衡场景）。 */
        SPREAD,
        /** 聚集：副本尽量聚集到少数集群（适合节省资源和成本的场景）。 */
        AGGREGATED
    }
}