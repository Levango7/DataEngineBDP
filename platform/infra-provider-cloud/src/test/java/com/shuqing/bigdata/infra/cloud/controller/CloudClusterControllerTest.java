package com.shuqing.bigdata.infra.cloud.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shuqing.bigdata.infra.cloud.model.CloudClusterInfo;
import com.shuqing.bigdata.infra.cloud.model.CloudClusterRequest;
import com.shuqing.bigdata.infra.cloud.model.ClusterScaleRequest;
import com.shuqing.bigdata.infra.cloud.model.VMSpec;
import com.shuqing.bigdata.infra.cloud.service.CloudProviderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link CloudClusterController} Web MVC 切片测试。
 *
 * <p>使用 {@link WebMvcTest} 加载 Controller 与 Security 配置，Mock Service 层，
 * 验证 REST API 路径、请求体绑定与响应状态码。</p>
 */
@DisplayName("CloudClusterController REST API 测试")
@WebMvcTest(CloudClusterController.class)
class CloudClusterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CloudProviderService cloudProviderService;

    @Test
    @DisplayName("GET /api/v1/clusters/cloud/providers 返回支持的 provider 列表")
    void listProvidersShouldReturn200() throws Exception {
        when(cloudProviderService.listSupportedProviders())
                .thenReturn(List.of("huawei", "ali", "tencent"));

        mockMvc.perform(get("/api/v1/clusters/cloud/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0]").value("huawei"))
                .andExpect(jsonPath("$.providers[1]").value("ali"))
                .andExpect(jsonPath("$.providers[2]").value("tencent"));
    }

    @Test
    @DisplayName("GET /api/v1/clusters/cloud/{provider} 返回集群列表")
    void listClustersShouldReturn200() throws Exception {
        CloudClusterInfo info = CloudClusterInfo.builder()
                .clusterId("c1").clusterName("test").provider("huawei").status("RUNNING").build();
        when(cloudProviderService.listClusters(anyString()))
                .thenReturn(List.of(info));

        mockMvc.perform(get("/api/v1/clusters/cloud/huawei"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].clusterId").value("c1"));
    }

    @Test
    @DisplayName("GET /api/v1/clusters/cloud/{provider}/{id} 集群不存在返回 404")
    void getClusterNotFoundShouldReturn404() throws Exception {
        when(cloudProviderService.getCluster(anyString(), anyString()))
                .thenReturn(null);

        mockMvc.perform(get("/api/v1/clusters/cloud/huawei/non-existent"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/v1/clusters/cloud/{provider}/{id} 销毁集群返回 200")
    void destroyClusterShouldReturn200() throws Exception {
        CloudClusterInfo info = CloudClusterInfo.builder()
                .clusterId("c1").status("DELETED").build();
        when(cloudProviderService.destroyCluster(anyString(), anyString()))
                .thenReturn(info);

        mockMvc.perform(delete("/api/v1/clusters/cloud/huawei/c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"));
    }

    @Test
    @DisplayName("POST /api/v1/clusters/cloud/{provider}/{id}/scale 扩缩容返回 200")
    void scaleClusterShouldReturn200() throws Exception {
        CloudClusterInfo info = CloudClusterInfo.builder()
                .clusterId("c1").status("RUNNING").build();
        when(cloudProviderService.scaleCluster(anyString(), anyString(), anyInt()))
                .thenReturn(info);

        ClusterScaleRequest req = ClusterScaleRequest.builder()
                .targetNodeCount(5).reason("scale-out").build();

        mockMvc.perform(post("/api/v1/clusters/cloud/huawei/c1/scale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }
}