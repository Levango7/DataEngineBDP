package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.repository.ApiKeyRepository;
import com.levango7.dataenginebdp.encaps.service.GatewayStatsService;
import com.levango7.dataenginebdp.encaps.util.CredentialEncryptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * GatewayController API Key 创建幂等性测试（A3）。
 *
 * <p>同租户同名 Key → 409 + messageKey；未冲突 → 正常创建。
 * Repository mock 隔离，无需数据库。</p>
 */
@ExtendWith(MockitoExtension.class)
class GatewayControllerIdempotencyTest {

    @Mock
    private ApiKeyRepository repository;

    @Mock
    private GatewayStatsService statsService;

    private GatewayController controller;

    /**
     * 测试用 AES-256 密钥（32 字节，仅用于单元测试，非生产密钥）。
     * GatewayController 依赖 CredentialEncryptor 对 apiKey 做加密落库，
     * 这里用真实加密器（纯 POJO，无需 Spring 容器），避免 mock final 类。
     */
    private static final byte[] TEST_AES_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    @BeforeEach
    void setUpTenant() {
        TenantContext.setTenantId("tenant_a");
        controller = new GatewayController(repository, statsService,
                new CredentialEncryptor(TEST_AES_KEY));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("同租户同名 Key：返回 409 + messageKey，不落库")
    void duplicateNameReturns409() {
        when(repository.existsByTenantIdAndName("tenant_a", "prod-key")).thenReturn(true);

        ResponseEntity<Map<String, Object>> resp = controller.createApiKey(
                new GatewayController.CreateKeyRequest("prod-key", "gpt-4o", 100, "chat"));

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        Map<String, Object> body = resp.getBody();
        assertThat(body.get("code")).isEqualTo(40901);
        assertThat(body.get("messageKey")).isEqualTo("error.resource.conflict");
        assertThat(body.get("conflictField")).isEqualTo("name");
    }

    @Test
    @DisplayName("名称不冲突：正常创建并一次性返回 secret")
    void uniqueNameCreates() {
        when(repository.existsByTenantIdAndName("tenant_a", "fresh-key")).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<Map<String, Object>> resp = controller.createApiKey(
                new GatewayController.CreateKeyRequest("fresh-key", "gpt-4o", 100, null));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = resp.getBody();
        assertThat(body.get("apiKey")).asString().startsWith("sk-");
        assertThat(body.get("secret")).asString().isNotEmpty();
        assertThat(body.get("secretShownOnce")).isEqualTo(true);
    }
}
