package com.levango7.dataenginebdp.infra.orchestrator.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 跨环境统一供给抽象 - 环境类型枚举。
 *
 * <p>L0.5 编排层定义 7 种环境类型，覆盖数据引擎大数据平台的全部基础设施供应场景。
 * 每个枚举值对应一个下游 Provider 的 REST 路由前缀与子类型：</p>
 *
 * <ul>
 *   <li>{@link #XINCHANG}         - 信创环境（国产 CPU + 国产 OS 物理机），路由到 infra-provider-xinchang</li>
 *   <li>{@link #BAREMETAL}        - 本地数据中心裸金属，路由到 infra-provider-baremetal</li>
 *   <li>{@link #CLOUD_HUAWEI}     - 公有云华为云 CCE，路由到 infra-provider-cloud/huawei</li>
 *   <li>{@link #CLOUD_ALI}        - 公有云阿里云 ACK，路由到 infra-provider-cloud/ali</li>
 *   <li>{@link #CLOUD_TENCENT}    - 公有云腾讯云 TKE，路由到 infra-provider-cloud/tencent</li>
 *   <li>{@link #PRIVATE_VSPHERE}  - 私有云 vSphere，路由到 infra-provider-private/vsphere</li>
 *   <li>{@link #PRIVATE_OPENSTACK} - 私有云 OpenStack，路由到 infra-provider-private/openstack</li>
 * </ul>
 *
 * <p>枚举同时承载路由元信息（{@code providerKind} 与 {@code subType}），
 * 供 {@link com.levango7.dataenginebdp.infra.orchestrator.registry.ProviderRegistry} 与
 * {@link com.levango7.dataenginebdp.infra.orchestrator.service.SupplyOrchestrator} 在运行时构造下游 URL。</p>
 */
public enum EnvironmentType {

    /**
     * 信创环境：鲲鹏/海光/飞腾/兆芯 + 麒麟 V10/统信 UOS 物理机。
     * 下游 Provider：infra-provider-xinchang (Java, port 8090)。
     */
    XINCHANG("xinchang", "xinchang", "信创环境"),

    /**
     * 本地数据中心裸金属：标准 x86 物理机 + IPMI/PXE 装机。
     * 下游 Provider：infra-provider-baremetal (Go, port 8091)。
     */
    BAREMETAL("baremetal", "baremetal", "本地裸金属"),

    /**
     * 公有云华为云 CCE。
     * 下游 Provider：infra-provider-cloud (Java, port 8092)，子类型 huawei。
     */
    CLOUD_HUAWEI("cloud", "huawei", "公有云-华为云"),

    /**
     * 公有云阿里云 ACK。
     * 下游 Provider：infra-provider-cloud (Java, port 8092)，子类型 ali。
     */
    CLOUD_ALI("cloud", "ali", "公有云-阿里云"),

    /**
     * 公有云腾讯云 TKE。
     * 下游 Provider：infra-provider-cloud (Java, port 8092)，子类型 tencent。
     */
    CLOUD_TENCENT("cloud", "tencent", "公有云-腾讯云"),

    /**
     * 私有云 vSphere。
     * 下游 Provider：infra-provider-private (Java, port 8093)，子类型 vsphere。
     */
    PRIVATE_VSPHERE("private", "vsphere", "私有云-vSphere"),

    /**
     * 私有云 OpenStack。
     * 下游 Provider：infra-provider-private (Java, port 8093)，子类型 openstack。
     */
    PRIVATE_OPENSTACK("private", "openstack", "私有云-OpenStack");

    /**
     * Provider 种类标识，对应下游 Provider 服务名前缀：
     * {@code xinchang} / {@code baremetal} / {@code cloud} / {@code private}。
     */
    private final String providerKind;

    /**
     * Provider 子类型，用于在多子类型的 Provider（cloud/private）中路由：
     * {@code xinchang} / {@code baremetal} / {@code huawei} / {@code ali} / {@code tencent} / {@code vsphere} / {@code openstack}。
     */
    private final String subType;

    /**
     * 人类可读的中文描述。
     */
    private final String description;

    EnvironmentType(String providerKind, String subType, String description) {
        this.providerKind = providerKind;
        this.subType = subType;
        this.description = description;
    }

    /**
     * 获取 Provider 种类标识。
     *
     * @return provider kind，如 {@code cloud}
     */
    public String getProviderKind() {
        return providerKind;
    }

    /**
     * 获取 Provider 子类型。
     *
     * @return sub type，如 {@code huawei}
     */
    public String getSubType() {
        return subType;
    }

    /**
     * 获取中文描述。
     *
     * @return 描述文本
     */
    public String getDescription() {
        return description;
    }

    /**
     * 判断是否为公有云环境。
     *
     * @return true 表示华为/阿里/腾讯云
     */
    public boolean isCloud() {
        return this == CLOUD_HUAWEI || this == CLOUD_ALI || this == CLOUD_TENCENT;
    }

    /**
     * 判断是否为私有云环境。
     *
     * @return true 表示 vSphere 或 OpenStack
     */
    public boolean isPrivateCloud() {
        return this == PRIVATE_VSPHERE || this == PRIVATE_OPENSTACK;
    }

    /**
     * 判断是否为物理机环境（信创或裸金属）。
     *
     * @return true 表示 XINCHANG 或 BAREMETAL
     */
    public boolean isBareMetal() {
        return this == XINCHANG || this == BAREMETAL;
    }

    /**
     * 构造下游 Provider 的 REST API 路径前缀（不含 host:port）。
     *
     * <p>路由规则：</p>
     * <ul>
     *   <li>XINCHANG / BAREMETAL：{@code /api/v1/clusters/{providerKind}}</li>
     *   <li>CLOUD_* / PRIVATE_*：{@code /api/v1/clusters/{providerKind}/{subType}}</li>
     * </ul>
     *
     * @return REST 路径前缀，如 {@code /api/v1/clusters/cloud/huawei}
     */
    public String getRestPathPrefix() {
        if (isCloud() || isPrivateCloud()) {
            return "/api/v1/clusters/" + providerKind + "/" + subType;
        }
        return "/api/v1/clusters/" + providerKind;
    }

    /**
     * 从字符串解析环境类型，大小写不敏感。
     *
     * @param value 环境类型字符串
     * @return 对应枚举值
     * @throws IllegalArgumentException 若值不匹配任何枚举
     */
    public static EnvironmentType fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("environment type must not be blank");
        }
        try {
            return EnvironmentType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "unknown environment type: " + value + ", supported: " + allValues());
        }
    }

    /**
     * 列出全部枚举值的字符串形式，用于错误信息。
     *
     * @return 枚举值列表
     */
    public static List<String> allValues() {
        return Arrays.stream(values())
                .map(Enum::name)
                .toList();
    }

    /**
     * 获取所有环境类型的不可变集合，供注册表遍历使用。
     *
     * @return 全部环境类型
     */
    public static Set<EnvironmentType> allTypes() {
        return Arrays.stream(values()).collect(Collectors.toUnmodifiableSet());
    }
}