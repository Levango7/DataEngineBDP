package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.model.AssetEntity;
import com.levango7.dataenginebdp.encaps.model.StandardEntity;
import com.levango7.dataenginebdp.encaps.repository.AssetRepository;
import com.levango7.dataenginebdp.encaps.repository.StandardRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标准符合性检查端点单测（H2 + 真实 Repository）。
 *
 * <p>背景：此前标准只存储与统计引用（/summary 落标率），从不校验资产内容
 * 是否符合标准——本测试覆盖 /compliance 端点补齐的落地校验逻辑：
 * enum 码值 / string 命名正则 / amount 数值 / date 日期格式 / skipped 不适用。</p>
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class StandardComplianceTest {

    @Autowired private StandardRepository standardRepository;
    @Autowired private AssetRepository assetRepository;
    /** @DataJpaTest slice 不装配 @RestController，手动构造（依赖均为真实 JPA Repository）。 */
    private StandardController controller;

    @BeforeEach
    void setUpTenant() {
        TenantContext.setTenantId("tenant_std");
        TenantContext.setUserId("tester");
        controller = new StandardController(standardRepository, assetRepository);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private StandardEntity std(String name, String type, String rule) {
        return standardRepository.save(StandardEntity.builder()
                .name(name).type(type).rule(rule).status("active")
                .tenantId("tenant_std")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());
    }

    private AssetEntity asset(String name, String fullJson) {
        return assetRepository.save(AssetEntity.builder()
                .name(name).type("table").owner("tester").status("registered")
                .qualityScore(100).securityLevel("L1")
                .fullJson(fullJson).tenantId("tenant_std")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());
    }

    @Test
    @DisplayName("enum 标准：码值在集合内符合、集合外违反")
    void enumStandard_complianceAndViolation() {
        std("性别枚举", "enum", "M,F,UNKNOWN");
        asset("dws.user", "{\"values\":[\"M\",\"F\"]}");
        asset("dws.bad_user", "{\"values\":[\"M\",\"X\"]}");

        ResponseEntity<Map<String, Object>> resp = controller.compliance();
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();

        List<Map<String, Object>> standards = (List<Map<String, Object>>) body.get("standards");
        assertThat(standards).hasSize(1);
        Map<String, Object> item = standards.get(0);
        assertThat(item.get("checked")).isEqualTo(2);
        assertThat(item.get("violations")).isEqualTo(1);
        assertThat((Boolean) item.get("compliant")).isFalse();
        List<Map<String, Object>> samples = (List<Map<String, Object>>) item.get("violationSamples");
        assertThat(samples).extracting("assetName").containsExactly("dws.bad_user");
        assertThat((Double) body.get("complianceRate")).isEqualTo(50.0);
    }

    @Test
    @DisplayName("string 标准：命名正则匹配")
    void stringStandard_namingRegex() {
        std("表命名规范", "string", "^[a-z]+(_[a-z]+)*$");
        asset("ods_orders", "{}");
        asset("Bad-Name", "{}");

        Map<String, Object> body = controller.compliance().getBody();
        List<Map<String, Object>> standards = (List<Map<String, Object>>) body.get("standards");
        assertThat(standards.get(0).get("violations")).isEqualTo(1);
        assertThat(standards.get(0).get("checked")).isEqualTo(2);
    }

    @Test
    @DisplayName("amount 标准：数值格式校验")
    void amountStandard_numericCheck() {
        std("金额标准", "amount", "decimal(18,2)");
        asset("dws.order", "{\"amount\":\"1234.56\"}");
        asset("dws.bad_order", "{\"amount\":\"abc\"}");
        asset("dws.no_amount", "{}"); // 缺字段 → skipped

        Map<String, Object> body = controller.compliance().getBody();
        List<Map<String, Object>> standards = (List<Map<String, Object>>) body.get("standards");
        assertThat(standards.get(0).get("checked")).isEqualTo(2);
        assertThat(standards.get(0).get("violations")).isEqualTo(1);
    }

    @Test
    @DisplayName("date 标准：ISO 日期格式校验")
    void dateStandard_isoFormat() {
        std("日期标准", "date", "ISO-8601");
        asset("ods.event", "{\"date\":\"2026-09-01\"}");
        asset("ods.bad_event", "{\"date\":\"09/01/2026 x\"}");

        Map<String, Object> body = controller.compliance().getBody();
        List<Map<String, Object>> standards = (List<Map<String, Object>>) body.get("standards");
        assertThat(standards.get(0).get("violations")).isEqualTo(1);
    }

    @Test
    @DisplayName("字段缺失资产不计入（skipped）；全部符合时 compliant=true")
    void allCompliant_marksTrue() {
        std("性别枚举", "enum", "M,F");
        asset("dws.user", "{\"values\":[\"M\"]}");
        asset("dws.empty", "{}");

        Map<String, Object> body = controller.compliance().getBody();
        List<Map<String, Object>> standards = (List<Map<String, Object>>) body.get("standards");
        Map<String, Object> item = standards.get(0);
        assertThat(item.get("checked")).isEqualTo(1);
        assertThat((Boolean) item.get("compliant")).isTrue();
        assertThat(body.get("totalViolations")).isEqualTo(0);
        assertThat((Double) body.get("complianceRate")).isEqualTo(100.0);
    }

    @Test
    @DisplayName("租户隔离：其他租户资产不参与校验")
    void tenantIsolation_otherTenantAssetsExcluded() {
        std("命名规范", "string", "^ok_.*$");
        asset("ok_table", "{}");
        // 他租户资产（直接构造，绕过租户上下文写库）
        assetRepository.save(AssetEntity.builder()
                .name("EVIL TABLE").type("table").owner("x").status("registered")
                .qualityScore(100).securityLevel("L1")
                .fullJson("{}").tenantId("tenant_other")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());

        Map<String, Object> body = controller.compliance().getBody();
        List<Map<String, Object>> standards = (List<Map<String, Object>>) body.get("standards");
        assertThat(standards.get(0).get("checked")).isEqualTo(1);
        assertThat((Boolean) standards.get(0).get("compliant")).isTrue();
    }

    @Test
    @DisplayName("非法 fullJson 资产记为违反（含原因）")
    void malformedFullJson_reportedAsViolation() {
        std("性别枚举", "enum", "M,F");
        asset("dws.broken", "not-json{{");

        Map<String, Object> body = controller.compliance().getBody();
        List<Map<String, Object>> standards = (List<Map<String, Object>>) body.get("standards");
        Map<String, Object> item = standards.get(0);
        assertThat(item.get("violations")).isEqualTo(1);
        List<Map<String, Object>> samples = (List<Map<String, Object>>) item.get("violationSamples");
        assertThat(samples.get(0).get("reason").toString()).contains("非法 JSON");
    }
}
