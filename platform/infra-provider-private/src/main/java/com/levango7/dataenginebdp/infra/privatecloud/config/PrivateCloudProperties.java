package com.levango7.dataenginebdp.infra.privatecloud.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 私有云 Provider 配置属性。
 *
 * <p>绑定 {@code application.yml} 中 {@code private-cloud.*} 配置项，
 * 包含 vSphere、OpenStack 与 K8s 引导三组配置。</p>
 *
 * <p>配置示例见 {@code src/main/resources/application.yml}。</p>
 *
 * @author shuqing-bigdata
 */
@ConfigurationProperties(prefix = "private-cloud")
public class PrivateCloudProperties {

    /**
     * vSphere（VMware vCenter）相关配置。
     */
    private VSphere vsphere = new VSphere();

    /**
     * OpenStack（Nova）相关配置。
     */
    private OpenStack openstack = new OpenStack();

    /**
     * K8s 集群引导配置。
     */
    private K8sBootstrap k8sBootstrap = new K8sBootstrap();

    public VSphere getVsphere() {
        return vsphere;
    }

    public void setVsphere(VSphere vsphere) {
        this.vsphere = vsphere;
    }

    public OpenStack getOpenstack() {
        return openstack;
    }

    public void setOpenstack(OpenStack openstack) {
        this.openstack = openstack;
    }

    public K8sBootstrap getK8sBootstrap() {
        return k8sBootstrap;
    }

    public void setK8sBootstrap(K8sBootstrap k8sBootstrap) {
        this.k8sBootstrap = k8sBootstrap;
    }

    /**
     * vSphere（VMware vCenter REST API）配置。
     */
    public static class VSphere {
        /** vCenter REST API 基址，例如 {@code https://vcenter.example.com} */
        private String vcenterUrl = "https://vcenter.example.com";
        /** vCenter 登录用户名 */
        private String username = "administrator@vsphere.local";
        /** vCenter 登录密码 */
        private String password = "";
        /** 克隆源模板 VM 名称 */
        private String templateVm = "ubuntu-2204-k8s-template";
        /** 目标数据中心名称 */
        private String datacenter = "Datacenter";
        /** 目标集群名称 */
        private String cluster = "Cluster";
        /** 目标 datastore 名称 */
        private String datastore = "datastore1";
        /** VM 文件夹名称 */
        private String folder = "shuqing-k8s";
        /** 是否跳过 TLS 证书校验（自签证书环境） */
        private boolean insecureTls = true;
        /** 连接建立超时（毫秒） */
        private int connectTimeoutMs = 5000;
        /** 读超时（毫秒） */
        private int readTimeoutMs = 30000;

        public String getVcenterUrl() { return vcenterUrl; }
        public void setVcenterUrl(String vcenterUrl) { this.vcenterUrl = vcenterUrl; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getTemplateVm() { return templateVm; }
        public void setTemplateVm(String templateVm) { this.templateVm = templateVm; }
        public String getDatacenter() { return datacenter; }
        public void setDatacenter(String datacenter) { this.datacenter = datacenter; }
        public String getCluster() { return cluster; }
        public void setCluster(String cluster) { this.cluster = cluster; }
        public String getDatastore() { return datastore; }
        public void setDatastore(String datastore) { this.datastore = datastore; }
        public String getFolder() { return folder; }
        public void setFolder(String folder) { this.folder = folder; }
        public boolean isInsecureTls() { return insecureTls; }
        public void setInsecureTls(boolean insecureTls) { this.insecureTls = insecureTls; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    }

    /**
     * OpenStack（Nova v2.1）配置。
     */
    public static class OpenStack {
        /** Keystone V3 认证端点 */
        private String authUrl = "http://keystone-service:5000/v3";
        /** 用户名 */
        private String username = "admin";
        /** 密码 */
        private String password = "";
        /** 项目（租户）名 */
        private String projectName = "admin";
        /** 用户所属 Domain */
        private String userDomainName = "Default";
        /** 项目所属 Domain */
        private String projectDomainName = "Default";
        /** 区域 */
        private String region = "RegionOne";
        /** 用于创建实例的镜像 ID */
        private String imageId = "";
        /** flavor ID */
        private String flavorId = "";
        /** 外部网络名称（用于分配浮动 IP） */
        private String externalNetwork = "public";
        /** 是否跳过 TLS 证书校验 */
        private boolean insecureTls = false;
        /** 连接建立超时（毫秒） */
        private int connectTimeoutMs = 5000;
        /** 读超时（毫秒） */
        private int readTimeoutMs = 30000;

        public String getAuthUrl() { return authUrl; }
        public void setAuthUrl(String authUrl) { this.authUrl = authUrl; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getProjectName() { return projectName; }
        public void setProjectName(String projectName) { this.projectName = projectName; }
        public String getUserDomainName() { return userDomainName; }
        public void setUserDomainName(String userDomainName) { this.userDomainName = userDomainName; }
        public String getProjectDomainName() { return projectDomainName; }
        public void setProjectDomainName(String projectDomainName) { this.projectDomainName = projectDomainName; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getImageId() { return imageId; }
        public void setImageId(String imageId) { this.imageId = imageId; }
        public String getFlavorId() { return flavorId; }
        public void setFlavorId(String flavorId) { this.flavorId = flavorId; }
        public String getExternalNetwork() { return externalNetwork; }
        public void setExternalNetwork(String externalNetwork) { this.externalNetwork = externalNetwork; }
        public boolean isInsecureTls() { return insecureTls; }
        public void setInsecureTls(boolean insecureTls) { this.insecureTls = insecureTls; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    }

    /**
     * K8s 集群引导配置。
     */
    public static class K8sBootstrap {
        /** 引导方式：ssh / cloud-init */
        private String method = "cloud-init";
        /** K8s 版本 */
        private String k8sVersion = "v1.30.0";
        /** Pod CIDR */
        private String podCidr = "10.244.0.0/16";
        /** Service CIDR */
        private String serviceCidr = "10.96.0.0/12";
        /** 等待 VM 就绪超时（秒） */
        private int readyTimeoutSeconds = 600;

        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public String getK8sVersion() { return k8sVersion; }
        public void setK8sVersion(String k8sVersion) { this.k8sVersion = k8sVersion; }
        public String getPodCidr() { return podCidr; }
        public void setPodCidr(String podCidr) { this.podCidr = podCidr; }
        public String getServiceCidr() { return serviceCidr; }
        public void setServiceCidr(String serviceCidr) { this.serviceCidr = serviceCidr; }
        public int getReadyTimeoutSeconds() { return readyTimeoutSeconds; }
        public void setReadyTimeoutSeconds(int readyTimeoutSeconds) { this.readyTimeoutSeconds = readyTimeoutSeconds; }
    }
}