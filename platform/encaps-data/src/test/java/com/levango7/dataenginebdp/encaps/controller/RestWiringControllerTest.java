package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.repository.ApiDefinitionRepository;
import com.levango7.dataenginebdp.encaps.repository.AssetRepository;
import com.levango7.dataenginebdp.encaps.repository.StandardRepository;
import com.levango7.dataenginebdp.encaps.repository.TemplateRepository;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.service.ElasticsearchIndexer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 8 个 REST 接线端点统一单测（H2 + 真实 Repository）。
 */
@DataJpaTest
@ContextConfiguration(classes = com.levango7.dataenginebdp.encaps.EncapsDataApplication.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class RestWiringControllerTest {

    @Autowired private StandardRepository standardRepository;
    @Autowired private ApiDefinitionRepository apiRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private TemplateRepository templateRepository;

    private ElasticsearchIndexer unavailableEs() {
        // mock ES 不可用（测试走 LIKE 回退路径）
        return new ElasticsearchIndexer("http://127.0.0.1:1") {
            @Override
            public boolean isAvailable() {
                return false;
            }
        };
    }

    @BeforeEach
    void setUpTenant() {
        TenantContext.setTenantId("tenant_a");
        TenantContext.setUserId("tester");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void standard_crud() {
        var c = new StandardController(standardRepository, assetRepository);
        var created = c.create(new StandardController.StandardRequest("user_id", "string", "^[A-Z0-9]+$", "用户ID标准"));
        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
        String id = (String) created.getBody().get("id");

        var got = c.get(Long.valueOf(id));
        assertThat(got.getBody()).isNotNull();

        var list = c.list(null, 1, 10);
        assertThat((Number) list.getBody().get("total")).isEqualTo(1);

        c.delete(Long.valueOf(id));
        assertThat(standardRepository.countByTenantId("tenant_a")).isZero();
    }

    @Test
    void asset_crud() {
        var c = new AssetController(assetRepository);
        var created = c.create(new AssetController.AssetRequest("销售订单表", "table", "tenant_b", "订单数据", 95, "L3", null));
        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(created.getBody().get("qualityScore")).isEqualTo(95);

        var list = c.list(null, 1, 10);
        assertThat((Number) list.getBody().get("total")).isEqualTo(1);

        c.delete(Long.valueOf((String) created.getBody().get("id")));
        assertThat(assetRepository.countByTenantId("tenant_a")).isZero();
    }

    @Test
    void search_findsAcrossTables() {
        // 预置数据
        assetRepository.save(com.levango7.dataenginebdp.encaps.model.AssetEntity.builder()
                .name("订单明细表").type("table").owner("t1").description("包含订单明细")
                .status("published").qualityScore(90).securityLevel("L2")
                .fullJson("{}").tenantId("tenant_a")
                .createdAt(java.time.Instant.now()).updatedAt(java.time.Instant.now()).build());
        standardRepository.save(com.levango7.dataenginebdp.encaps.model.StandardEntity.builder()
                .name("order_no").type("string").rule("^ORD").description("订单号标准")
                .status("active").tenantId("tenant_a")
                .createdAt(java.time.Instant.now()).updatedAt(java.time.Instant.now()).build());

        var c = new SearchController(assetRepository, apiRepository, standardRepository, templateRepository, unavailableEs());
        var resp = c.search(new SearchController.SearchRequest("订单", "keyword", 1, 20));
        List<Map<String, Object>> list = (List<Map<String, Object>>) resp.getBody().get("list");
        assertThat(list).isNotEmpty(); // 命中"订单明细表"与"订单号标准"
        assertThat(((Number) resp.getBody().get("total")).longValue()).isGreaterThanOrEqualTo(1L);
        assertThat(resp.getBody()).containsKey("tookMs");
    }

    @Test
    void search_suggest() {
        assetRepository.save(com.levango7.dataenginebdp.encaps.model.AssetEntity.builder()
                .name("用户画像表").type("table").owner("t1").status("published")
                .qualityScore(80).securityLevel("L2").fullJson("{}").tenantId("tenant_a")
                .createdAt(java.time.Instant.now()).updatedAt(java.time.Instant.now()).build());

        var c = new SearchController(assetRepository, apiRepository, standardRepository, templateRepository, unavailableEs());
        var resp = c.suggest("用户");
        assertThat(resp.getBody()).contains("用户画像表");
    }
}
