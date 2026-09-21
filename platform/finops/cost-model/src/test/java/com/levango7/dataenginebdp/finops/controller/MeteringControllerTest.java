package com.levango7.dataenginebdp.finops.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.finops.model.QueryMeteringRecord;
import com.levango7.dataenginebdp.finops.model.QueryMeteringRequest;
import com.levango7.dataenginebdp.finops.repository.QueryMeteringRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MeteringController 单元测试（H2 内存库 + 真实 Repository）。
 */
@DataJpaTest
class MeteringControllerTest {

    private static final String TEST_TENANT_ID = "tenant_a";

    @Autowired
    private QueryMeteringRepository repository;

    /**
     * 生产控制器在 R8 加固后从 {@link TenantContext} 取 tenantId（忽略请求体），
     * 缺失返回 401。测试直接调用控制器方法（不过滤器链），需由测试侧写入上下文。
     */
    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TEST_TENANT_ID);
        TenantContext.setUserId("test-user");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private MeteringController controller() {
        return new MeteringController(repository);
    }

    private QueryMeteringRequest sampleRequest(String requestId) {
        return QueryMeteringRequest.builder()
                .tenantId("tenant_a")
                .namespace("analysis")
                .engine("trino")
                .sqlHash("abc123")
                .bytesScanned(1024L * 1024 * 100) // 100MB
                .estimated(true)
                .durationMs(250L)
                .clientRequestId(requestId)
                .build();
    }

    @Test
    void recordQuery_persistsAndReturnsCreated() {
        ResponseEntity<Map<String, Object>> resp =
                controller().recordQuery(sampleRequest("req-1"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(repository.count()).isEqualTo(1);
        QueryMeteringRecord saved = repository.findByTenantIdAndClientRequestId("tenant_a", "req-1").orElseThrow();
        assertThat(saved.getBytesScanned()).isEqualTo(1024L * 1024 * 100);
        assertThat(saved.isEstimated()).isTrue();
        assertThat(saved.getEngine()).isEqualTo("trino");
    }

    @Test
    void recordQuery_duplicateRequestIsIdempotent() {
        controller().recordQuery(sampleRequest("req-dup"));
        ResponseEntity<Map<String, Object>> dupResp =
                controller().recordQuery(sampleRequest("req-dup"));

        assertThat(dupResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) dupResp.getBody()).get("duplicate")).isEqualTo(true);
        assertThat(repository.count()).isEqualTo(1); // 不重复记账
    }

    @Test
    void distinctRequestsBothRecorded() {
        controller().recordQuery(sampleRequest("req-1"));
        controller().recordQuery(sampleRequest("req-2"));

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void recordQuery_withoutTenantContext_returns401() {
        // 清除上下文，验证 R8 的 fail-closed 语义未被测试改动削弱
        TenantContext.clear();

        ResponseEntity<Map<String, Object>> resp = controller().recordQuery(sampleRequest("req-no-tenant"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(repository.count()).isEqualTo(0L);
    }
}