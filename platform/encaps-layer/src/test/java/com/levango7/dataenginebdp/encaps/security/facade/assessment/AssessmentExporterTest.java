package com.levango7.dataenginebdp.encaps.security.facade.assessment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.encaps.security.facade.config.SecurityFacadeConfig;
import com.levango7.dataenginebdp.encaps.security.facade.evidence.EvidenceItem;
import com.levango7.dataenginebdp.encaps.security.facade.evidence.EvidenceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AssessmentExporter} 单元测试。
 */
class AssessmentExporterTest {

    @TempDir
    Path tempDir;

    private AssessmentExporter exporter;
    private SecurityFacadeConfig config;

    @BeforeEach
    void setUp() {
        config = new SecurityFacadeConfig();
        config.getAssessment().setReportDir(tempDir.toString());
        exporter = new AssessmentExporter(config, new ObjectMapper());
    }

    @Test
    @DisplayName("generate — 等保 2.0 报告包含 5 个控制项")
    void generate_dengbao20_shouldContain5Controls() {
        List<EvidenceItem> evidence = List.of(
                createEvidence(EvidenceType.AUDIT_LOG),
                createEvidence(EvidenceType.CONFIG_SNAPSHOT),
                createEvidence(EvidenceType.CRYPTO_OPERATION));

        AssessmentReport report = exporter.generate(
                AssessmentType.DENGBAO_2_0, evidence, "测试系统");

        assertThat(report.getType()).isEqualTo(AssessmentType.DENGBAO_2_0);
        assertThat(report.getSystemName()).isEqualTo("测试系统");
        assertThat(report.getControlItems()).hasSize(5);
        assertThat(report.getEvidenceIds()).hasSize(3);
        assertThat(report.getSummary()).contains("控制项总数=5");
    }

    @Test
    @DisplayName("generate — 密评报告包含 4 个控制项")
    void generate_miping_shouldContain4Controls() {
        List<EvidenceItem> evidence = List.of(createEvidence(EvidenceType.CRYPTO_OPERATION));

        AssessmentReport report = exporter.generate(
                AssessmentType.MIPING, evidence, "测试系统");

        assertThat(report.getControlItems()).hasSize(4);
    }

    @Test
    @DisplayName("generate — PIPIA 报告包含 2 个控制项")
    void generate_pipia_shouldContain2Controls() {
        AssessmentReport report = exporter.generate(
                AssessmentType.PIPIA, List.of(), "测试系统");

        assertThat(report.getControlItems()).hasSize(2);
    }

    @Test
    @DisplayName("complianceRate — 有证据时合规率较高")
    void complianceRate_withEvidence_shouldBeHigh() {
        List<EvidenceItem> evidence = List.of(
                createEvidence(EvidenceType.AUDIT_LOG),
                createEvidence(EvidenceType.CONFIG_SNAPSHOT),
                createEvidence(EvidenceType.CRYPTO_OPERATION));

        AssessmentReport report = exporter.generate(
                AssessmentType.DENGBAO_2_0, evidence, "测试系统");

        // 5 个控制项，有审计+配置+加密证据，应该大部分 COMPLIANT
        assertThat(report.complianceRate()).isGreaterThan(0.5);
    }

    @Test
    @DisplayName("complianceRate — 无证据时合规率较低")
    void complianceRate_noEvidence_shouldBeLow() {
        AssessmentReport report = exporter.generate(
                AssessmentType.DENGBAO_2_0, List.of(), "测试系统");

        // 无证据，大部分 PARTIAL 或 NON_COMPLIANT
        assertThat(report.complianceRate()).isLessThanOrEqualTo(0.5);
    }

    @Test
    @DisplayName("export — 写入报告文件")
    void export_shouldWriteFile() throws IOException {
        List<EvidenceItem> evidence = List.of(createEvidence(EvidenceType.AUDIT_LOG));

        Path path = exporter.export(AssessmentType.DENGBAO_2_0, evidence, "测试系统");

        assertThat(path).exists();
        assertThat(path.getFileName().toString()).startsWith("dengbao-2.0-");
        assertThat(path.getFileName().toString()).endsWith(".json");
    }

    @Test
    @DisplayName("exportWithDefaultStandard — 使用配置中的默认标准")
    void exportWithDefaultStandard_shouldUseConfig() throws IOException {
        config.getAssessment().setDefaultStandard("miping");

        Path path = exporter.exportWithDefaultStandard(List.of(), "测试系统");

        assertThat(path).exists();
        assertThat(path.getFileName().toString()).startsWith("miping-");
    }

    @Test
    @DisplayName("AssessmentType.fromCode — 未知代码抛异常")
    void fromCode_unknown_shouldThrow() {
        assertThatThrownBy(() -> AssessmentType.fromCode("unknown"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AssessmentType.fromCode — 大小写不敏感")
    void fromCode_caseInsensitive_shouldWork() {
        assertThat(AssessmentType.fromCode("DENGBAO-2.0")).isEqualTo(AssessmentType.DENGBAO_2_0);
        assertThat(AssessmentType.fromCode("Miping")).isEqualTo(AssessmentType.MIPING);
    }

    @Test
    @DisplayName("ControlItemStatus — toMap 包含所有字段")
    void controlItemStatus_toMap_shouldContainAllFields() {
        ControlItemStatus status = new ControlItemStatus(
                "8.1.4.3", "安全审计", "审计要求",
                ComplianceStatus.COMPLIANT,
                List.of("ev-1", "ev-2"),
                null, null);

        Map<String, Object> map = status.toMap();
        assertThat(map).containsEntry("controlId", "8.1.4.3");
        assertThat(map).containsEntry("title", "安全审计");
        assertThat(map).containsEntry("status", "COMPLIANT");
        assertThat(map).containsEntry("statusLabel", "符合");
        assertThat(map).containsEntry("evidenceIds", List.of("ev-1", "ev-2"));
    }

    @Test
    @DisplayName("ComplianceStatus.isPassing — COMPLIANT 与 NOT_APPLICABLE 为 true")
    void complianceStatus_isPassing() {
        assertThat(ComplianceStatus.COMPLIANT.isPassing()).isTrue();
        assertThat(ComplianceStatus.NOT_APPLICABLE.isPassing()).isTrue();
        assertThat(ComplianceStatus.PARTIAL.isPassing()).isFalse();
        assertThat(ComplianceStatus.NON_COMPLIANT.isPassing()).isFalse();
    }

    private EvidenceItem createEvidence(EvidenceType type) {
        return new EvidenceItem(
                java.util.UUID.randomUUID().toString(),
                type, Instant.now(),
                "test evidence", "test",
                Map.of("key", "value"), null);
    }
}