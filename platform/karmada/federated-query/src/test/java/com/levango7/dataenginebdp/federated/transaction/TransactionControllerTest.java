package com.levango7.dataenginebdp.federated.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TransactionController} REST API 单元测试（MockMvc 独立设置）。
 *
 * <p>覆盖事务开启、prepare、commit、rollback、查询、列表端点。
 */
@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class TransactionControllerTest {

    private ClusterTransactionClient client;
    private TwoPhaseCommitProtocol protocol;
    private IcebergSnapshotIsolation snapshotIsolation;
    private TransactionCoordinator coordinator;
    private TransactionController controller;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Map<String, String> ENDPOINTS = Map.of(
            "cluster-a", "http://a:8090",
            "cluster-b", "http://b:8090");

    @BeforeEach
    void setUp() {
        client = mock(ClusterTransactionClient.class);
        protocol = new TwoPhaseCommitProtocol(client, 2000, 2000, 3, 50);
        snapshotIsolation = new IcebergSnapshotIsolation();
        coordinator = new TransactionCoordinator(protocol, snapshotIsolation);
        controller = new TransactionController(coordinator);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldBeginTransactionViaPost() throws Exception {
        BeginTransactionRequest req = BeginTransactionRequest.builder()
                .participants(ENDPOINTS)
                .tableIds(List.of("db.t1"))
                .build();

        String responseBody = mockMvc.perform(post("/api/v1/federated/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).contains("\"status\":\"ACTIVE\"");
        assertThat(responseBody).contains("\"txId\":\"tx-");
        assertThat(responseBody).contains("db.t1");
    }

    @Test
    void shouldPrepareTransactionViaPost() throws Exception {
        when(client.prepare(anyString(), anyString(), anyString())).thenReturn(true);
        String txId = coordinator.begin(ENDPOINTS, List.of("db.t1"));

        mockMvc.perform(post("/api/v1/federated/transactions/{txId}/prepare", txId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREPARED"))
                .andExpect(jsonPath("$.txId").value(txId));
    }

    @Test
    void shouldCommitTransactionViaPost() throws Exception {
        when(client.prepare(anyString(), anyString(), anyString())).thenReturn(true);
        when(client.commit(anyString(), anyString(), anyString())).thenReturn(true);
        String txId = coordinator.begin(ENDPOINTS, List.of("db.t1"));
        coordinator.prepare(txId);

        mockMvc.perform(post("/api/v1/federated/transactions/{txId}/commit", txId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"));
    }

    @Test
    void shouldReturnServerErrorWhenCommitFails() throws Exception {
        when(client.prepare(anyString(), anyString(), anyString())).thenReturn(true);
        when(client.commit(anyString(), anyString(), anyString())).thenReturn(false);
        String txId = coordinator.begin(ENDPOINTS, List.of("db.t1"));
        coordinator.prepare(txId);

        mockMvc.perform(post("/api/v1/federated/transactions/{txId}/commit", txId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void shouldRollbackTransactionViaPost() throws Exception {
        lenient().when(client.rollback(anyString(), anyString(), anyString())).thenReturn(true);
        String txId = coordinator.begin(ENDPOINTS, List.of("db.t1"));

        mockMvc.perform(post("/api/v1/federated/transactions/{txId}/rollback", txId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"));
    }

    @Test
    void shouldGetTransactionStatusViaGet() throws Exception {
        String txId = coordinator.begin(ENDPOINTS, List.of("db.t1"));

        mockMvc.perform(get("/api/v1/federated/transactions/{txId}", txId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txId").value(txId))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void shouldReturn404ForUnknownTransaction() throws Exception {
        mockMvc.perform(get("/api/v1/federated/transactions/{txId}", "nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldListAllTransactionsViaGet() throws Exception {
        String txId1 = coordinator.begin(ENDPOINTS, List.of());
        String txId2 = coordinator.begin(ENDPOINTS, List.of());

        mockMvc.perform(get("/api/v1/federated/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.data[0].txId").exists())
                .andExpect(jsonPath("$.data[1].txId").exists());
    }

    @Test
    void shouldReturnEmptyListWhenNoTransactions() throws Exception {
        mockMvc.perform(get("/api/v1/federated/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void shouldRejectBeginWithoutParticipants() throws Exception {
        BeginTransactionRequest req = BeginTransactionRequest.builder()
                .participants(Map.of())
                .tableIds(List.of())
                .build();

        mockMvc.perform(post("/api/v1/federated/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }
}