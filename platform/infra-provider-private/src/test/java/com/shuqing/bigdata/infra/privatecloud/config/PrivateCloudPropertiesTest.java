package com.shuqing.bigdata.infra.privatecloud.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PrivateCloudProperties 配置绑定测试。
 *
 * @author shuqing-bigdata
 */
class PrivateCloudPropertiesTest {

    @Test
    @DisplayName("默认构造 — vSphere 配置默认值正确")
    void vsphere_defaults_shouldBeCorrect() {
        PrivateCloudProperties.VSphere vsphere = new PrivateCloudProperties.VSphere();
        assertEquals("https://vcenter.example.com", vsphere.getVcenterUrl());
        assertEquals("administrator@vsphere.local", vsphere.getUsername());
        assertEquals("ubuntu-2204-k8s-template", vsphere.getTemplateVm());
        assertEquals("Datacenter", vsphere.getDatacenter());
        assertEquals("Cluster", vsphere.getCluster());
        assertEquals("datastore1", vsphere.getDatastore());
        assertEquals("shuqing-k8s", vsphere.getFolder());
        assertTrue(vsphere.isInsecureTls());
        assertEquals(5000, vsphere.getConnectTimeoutMs());
        assertEquals(30000, vsphere.getReadTimeoutMs());
    }

    @Test
    @DisplayName("默认构造 — OpenStack 配置默认值正确")
    void openstack_defaults_shouldBeCorrect() {
        PrivateCloudProperties.OpenStack openstack = new PrivateCloudProperties.OpenStack();
        assertEquals("http://keystone-service:5000/v3", openstack.getAuthUrl());
        assertEquals("admin", openstack.getUsername());
        assertEquals("admin", openstack.getProjectName());
        assertEquals("Default", openstack.getUserDomainName());
        assertEquals("Default", openstack.getProjectDomainName());
        assertEquals("RegionOne", openstack.getRegion());
        assertEquals("public", openstack.getExternalNetwork());
        assertFalse(openstack.isInsecureTls());
    }

    @Test
    @DisplayName("默认构造 — K8s 引导配置默认值正确")
    void k8sBootstrap_defaults_shouldBeCorrect() {
        PrivateCloudProperties.K8sBootstrap k8s = new PrivateCloudProperties.K8sBootstrap();
        assertEquals("cloud-init", k8s.getMethod());
        assertEquals("v1.30.0", k8s.getK8sVersion());
        assertEquals("10.244.0.0/16", k8s.getPodCidr());
        assertEquals("10.96.0.0/12", k8s.getServiceCidr());
        assertEquals(600, k8s.getReadyTimeoutSeconds());
    }

    @Test
    @DisplayName("顶层配置 — 默认包含三组子配置")
    void topLevel_defaults_shouldContainAllSections() {
        PrivateCloudProperties properties = new PrivateCloudProperties();
        assertNotNull(properties.getVsphere());
        assertNotNull(properties.getOpenstack());
        assertNotNull(properties.getK8sBootstrap());
    }

    @Test
    @DisplayName("setter — 可正确更新字段")
    void setter_shouldUpdateFields() {
        PrivateCloudProperties.VSphere vsphere = new PrivateCloudProperties.VSphere();
        vsphere.setVcenterUrl("https://vcenter.prod.example.com");
        vsphere.setUsername("admin@prod");
        vsphere.setInsecureTls(false);

        assertEquals("https://vcenter.prod.example.com", vsphere.getVcenterUrl());
        assertEquals("admin@prod", vsphere.getUsername());
        assertFalse(vsphere.isInsecureTls());
    }
}