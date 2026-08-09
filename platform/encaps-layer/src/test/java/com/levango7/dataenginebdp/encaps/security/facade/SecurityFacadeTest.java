package com.levango7.dataenginebdp.encaps.security.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.encaps.crypto.CryptoConfig;
import com.levango7.dataenginebdp.encaps.crypto.CryptoSpiFactory;
import com.levango7.dataenginebdp.encaps.security.facade.assessment.AssessmentExporter;
import com.levango7.dataenginebdp.encaps.security.facade.assessment.AssessmentReport;
import com.levango7.dataenginebdp.encaps.security.facade.assessment.AssessmentType;
import com.levango7.dataenginebdp.encaps.security.facade.audit.AuditFacade;
import com.levango7.dataenginebdp.encaps.security.facade.auth.AuthFacade;
import com.levango7.dataenginebdp.encaps.security.facade.config.SecurityFacadeConfig;
import com.levango7.dataenginebdp.encaps.security.facade.crypto.CryptoFacade;
import com.levango7.dataenginebdp.encaps.security.facade.evidence.EvidenceArchive;
import com.levango7.dataenginebdp.encaps.security.facade.evidence.EvidenceCollector;
import com.levango7.dataenginebdp.encaps.security.facade.evidence.EvidenceItem;
import com.levango7.dataenginebdp.encaps.security.facade.mask.MaskFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SecurityFacade} 统一入口单元测试。
 *
 * <p>验证四大子能力访问器、证据收集归档、测评导出的端到端流程。</p>
 */
class SecurityFacadeTest {

    @TempDir
    Path tempDir;

    private SecurityFacade facade;
    private SecurityFacadeConfig config;

    @BeforeEach
    void setUp() {
        config = new SecurityFacadeConfig();
        config.getEvidence().setArchiveDir(tempDir.resolve("evidence").toString());
        config.getAssessment().setReportDir(tempDir.resolve("assessment").toString());

        CryptoSpiFactory spiFactory = new CryptoSpiFactory(new CryptoConfig());
        CryptoFacade cryptoFacade = new CryptoFacade(spiFactory, config);
        MaskFacade maskFacade = new MaskFacade(config);
        AuditFacade auditFacade = new AuditFacade(config);
        AuthFacade authFacade = new AuthFacade(config);
        EvidenceCollector collector = new EvidenceCollector(auditFacade, cryptoFacade, maskFacade, config);
        EvidenceArchive archive = new EvidenceArchive(config, new ObjectMapper());
        AssessmentExporter exporter = new AssessmentExporter(config, new ObjectMapper());

        facade = new SecurityFacade(config, cryptoFacade, maskFacade, auditFacade,
                authFacade, collector, archive, exporter);
    }

    @Test
    @DisplayName("子能力访问器 — 返回非 null 实例")
    void accessors_shouldReturnNonNullInstances() {
        assertThat(facade.crypto()).isNotNull();
        assertThat(facade.mask()).isNotNull();
        assertThat(facade.audit()).isNotNull();
        assertThat(facade.auth()).isNotNull();
    }

    @Test
    @DisplayName("isEnabled — 默认 true")
    void isEnabled_defaultTrue() {
        assertThat(facade.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("collectEvidence — 收集审计日志 + 配置快照")
    void collectEvidence_shouldCollectAuditAndConfig() {
        facade.audit().record("LOGIN").tenantId("t1").userId("u1").build();

        List<EvidenceItem> evidence = facade.collectEvidence();

        // 1 审计 + 1 配置快照
        assertThat(evidence).hasSize(2);
    }

    @Test
    @DisplayName("collectAndArchiveEvidence — 收集并归档，返回含校验和")
    void collectAndArchiveEvidence_shouldReturnWithChecksum() throws IOException {
        facade.audit().record("TEST").build();

        List<EvidenceItem> archived = facade.collectAndArchiveEvidence();

        assertThat(archived).isNotEmpty();
        assertThat(archived).allSatisfy(item -> assertThat(item.getChecksum()).isNotBlank());
    }

    @Test
    @DisplayName("generateAssessment — 生成等保 2.0 报告")
    void generateAssessment_shouldGenerateDengbaoReport() {
        facade.audit().record("TEST").build();
        List<EvidenceItem> evidence = facade.collectEvidence();

        AssessmentReport report = facade.generateAssessment(
                AssessmentType.DENGBAO_2_0, evidence, "数据引擎大数据平台");

        assertThat(report).isNotNull();
        assertThat(report.getControlItems()).isNotEmpty();
        assertThat(report.getSystemName()).isEqualTo("数据引擎大数据平台");
    }

    @Test
    @DisplayName("exportAssessment — 写入报告文件")
    void exportAssessment_shouldWriteFile() throws IOException {
        facade.audit().record("TEST").build();
        List<EvidenceItem> evidence = facade.collectEvidence();

        Path path = facade.exportAssessment(AssessmentType.DENGBAO_2_0, evidence, "数据引擎大数据平台");

        assertThat(path).exists();
        assertThat(path.getFileName().toString()).startsWith("dengbao-2.0-");
    }

    @Test
    @DisplayName("collectAndExportAssessment — 一键收集证据并导出报告")
    void collectAndExportAssessment_shouldWorkEndToEnd() throws IOException {
        facade.audit().record("ENCRYPT").build();
        facade.audit().record("DECRYPT").build();

        Path path = facade.collectAndExportAssessment(AssessmentType.MIPING, "数据引擎大数据平台");

        assertThat(path).exists();
        assertThat(path.getFileName().toString()).startsWith("miping-");
    }

    @Test
    @DisplayName("禁用后调用抛 IllegalStateException")
    void disabled_shouldThrow() {
        config.setEnabled(false);

        assertThatThrownBy(() -> facade.collectEvidence())
                .isInstanceOf(IllegalStateException.class);
    }
}
