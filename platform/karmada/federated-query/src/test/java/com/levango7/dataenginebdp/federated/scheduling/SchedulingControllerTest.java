package com.levango7.dataenginebdp.federated.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SchedulingController} REST API 单元测试（MockMvc 独立设置）。
 */
@ExtendWith(MockitoExtension.class)
class SchedulingControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private FederatedScheduler scheduler;

    @InjectMocks
    private SchedulingController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void createPolicy_shouldReturnCreatedPolicy() throws Exception {
        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("test-policy")
                .namespace("default")
                .workloadName("my-app")
                .build();
        when(scheduler.registerPolicy(any(SchedulingPolicy.class))).thenReturn(policy);

        mockMvc.perform(post("/api/v1/federated/scheduling/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(policy)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("created"))
                .andExpect(jsonPath("$.data.name").value("test-policy"));
    }

    @Test
    void listPolicies_shouldReturnAllPolicies() throws Exception {
        SchedulingPolicy p1 = SchedulingPolicy.builder().name("p1").build();
        SchedulingPolicy p2 = SchedulingPolicy.builder().name("p2").build();
        when(scheduler.listPolicies()).thenReturn(Arrays.asList(p1, p2));

        mockMvc.perform(get("/api/v1/federated/scheduling/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.data[0].name").value("p1"))
                .andExpect(jsonPath("$.data[1].name").value("p2"));
    }

    @Test
    void listPolicies_shouldReturnEmptyWhenNoneRegistered() throws Exception {
        when(scheduler.listPolicies()).thenReturn(new ArrayList<>());

        mockMvc.perform(get("/api/v1/federated/scheduling/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void decide_shouldReturnSchedulingDecision() throws Exception {
        FederatedScheduler.SchedulingDecision decision = FederatedScheduler.SchedulingDecision.builder()
                .decisionId("dec-123")
                .policyName("test-policy")
                .workloadName("my-app")
                .replicas(2)
                .success(true)
                .timestamp(Instant.now())
                .distribution(new LinkedHashMap<>(Map.of("c1", 1, "c2", 1)))
                .build();
        when(scheduler.decide(any(FederatedScheduler.SchedulingInput.class))).thenReturn(decision);

        FederatedScheduler.SchedulingInput input = FederatedScheduler.SchedulingInput.builder()
                .workloadName("my-app")
                .replicas(2)
                .policyName("test-policy")
                .build();

        mockMvc.perform(post("/api/v1/federated/scheduling/decide")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.decisionId").value("dec-123"))
                .andExpect(jsonPath("$.data.distribution.c1").value(1))
                .andExpect(jsonPath("$.data.distribution.c2").value(1));
    }

    @Test
    void topology_shouldReturnEmptyViewByDefault() throws Exception {
        mockMvc.perform(get("/api/v1/federated/scheduling/topology"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filter").value("all"));
    }

    @Test
    void topology_shouldRespectClusterFilter() throws Exception {
        mockMvc.perform(get("/api/v1/federated/scheduling/topology")
                        .param("clusters", "c1,c2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filter").value("c1,c2"));
    }

    @Test
    void listDecisions_shouldReturnHistory() throws Exception {
        FederatedScheduler.SchedulingDecision d = FederatedScheduler.SchedulingDecision.builder()
                .decisionId("dec-1")
                .success(true)
                .timestamp(Instant.now())
                .build();
        when(scheduler.listDecisions(anyInt())).thenReturn(List.of(d));

        mockMvc.perform(get("/api/v1/federated/scheduling/decisions")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.data[0].decisionId").value("dec-1"));
    }

    @Test
    void generatePropagationPolicy_shouldReturnYaml() throws Exception {
        when(scheduler.generatePropagationPolicy("my-policy"))
                .thenReturn("apiVersion: policy.karmada.io/v1alpha1\nkind: PropagationPolicy\n");

        Map<String, String> request = Map.of("policyName", "my-policy");

        mockMvc.perform(post("/api/v1/federated/scheduling/propagation-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyName").value("my-policy"))
                .andExpect(jsonPath("$.yaml").exists());
    }

    @Test
    void generatePropagationPolicy_shouldRejectWithoutName() throws Exception {
        Map<String, String> request = new LinkedHashMap<>();

        mockMvc.perform(post("/api/v1/federated/scheduling/propagation-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}