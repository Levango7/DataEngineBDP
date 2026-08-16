package com.levango7.dataenginebdp.encaps.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.encaps.repository.AssetRepository;
import com.levango7.dataenginebdp.encaps.repository.DataSourceRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多租户隔离集成测试（真实 JWT token + MockMvc 全链路）。
 *
 * <p>验证：租户 A 创建数据资产 → 租户 B 的 token 无法看到（隔离生效）。
 * 用真实 JwtAuthFilter 验签（HMAC 密钥与测试一致），覆盖鉴权 → 租户上下文
 * → 仓储过滤的完整链路。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.security.jwt.secret=dev-secret-key-change-in-production-at-least-256-bits",
        "app.security.jwt.issuer=shuqing-bigdata",
        "app.security.oidc.enabled=false",
        "app.k8s.mock-enabled=true",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class MultiTenantIsolationTest {

    /** mock KubernetesClient：mock 模式下主配置返回 null，用 @MockBean 提供。 */
    @MockBean
    private KubernetesClient kubernetesClient;

    private static final String SECRET = "dev-secret-key-change-in-production-at-least-256-bits";
    private static final String ISSUER = "shuqing-bigdata";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private DataSourceRepository dataSourceRepository;

    private SecretKey signingKey;

    @BeforeEach
    void setUp() {
        signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        assetRepository.deleteAll();
        dataSourceRepository.deleteAll();
    }

    /** 生成租户 token。 */
    private String token(String tenantId) {
        return Jwts.builder()
                .subject("user-" + tenantId)
                .claim("tenantId", tenantId)
                .issuer(ISSUER)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(signingKey)
                .compact();
    }

    @Test
    void tenantA_data_invisibleToTenantB() throws Exception {
        // 租户 A 创建资产（路径已调整为 /api/v1/governance/assets，避免与 asset-exchange 冲突）
        mockMvc.perform(post("/api/v1/governance/assets")
                        .header("Authorization", "Bearer " + token("tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"secret-orders\",\"type\":\"table\",\"owner\":\"a\","
                                + "\"qualityScore\":90,\"securityLevel\":\"L2\"}"))
                .andExpect(status().isOk());

        // 租户 A 能看到自己的资产
        mockMvc.perform(get("/api/v1/governance/assets")
                        .header("Authorization", "Bearer " + token("tenant-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].name").value("secret-orders"));

        // 租户 B 看不到 A 的资产（隔离生效）
        mockMvc.perform(get("/api/v1/governance/assets")
                        .header("Authorization", "Bearer " + token("tenant-b")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void tenantA_datasource_invisibleToTenantB() throws Exception {
        // 租户 A 创建数据源
        mockMvc.perform(post("/api/v1/datasources")
                        .header("Authorization", "Bearer " + token("tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a-db\",\"type\":\"mysql\",\"host\":\"10.0.0.1\","
                                + "\"port\":3306,\"username\":\"root\",\"password\":\"p\"}"))
                .andExpect(status().isOk());

        // 租户 A 列表有 1 条
        MvcResult aResult = mockMvc.perform(get("/api/v1/datasources")
                        .header("Authorization", "Bearer " + token("tenant-a")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode aBody = MAPPER.readTree(aResult.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(aBody.size()).isEqualTo(1);

        // 租户 B 列表为空（看不到 A 的数据源）
        MvcResult bResult = mockMvc.perform(get("/api/v1/datasources")
                        .header("Authorization", "Bearer " + token("tenant-b")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode bBody = MAPPER.readTree(bResult.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(bBody.size()).isZero();
    }

    @Test
    void withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/governance/assets"))
                .andExpect(status().isUnauthorized());
    }
}
