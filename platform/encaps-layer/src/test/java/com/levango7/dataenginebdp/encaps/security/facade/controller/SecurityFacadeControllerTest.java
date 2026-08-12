package com.levango7.dataenginebdp.encaps.security.facade.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.encaps.crypto.CryptoConfig;
import com.levango7.dataenginebdp.encaps.crypto.CryptoSpiFactory;
import com.levango7.dataenginebdp.encaps.security.facade.SecurityFacade;
import com.levango7.dataenginebdp.encaps.security.facade.assessment.AssessmentExporter;
import com.levango7.dataenginebdp.encaps.security.facade.audit.AuditFacade;
import com.levango7.dataenginebdp.encaps.security.facade.auth.AuthFacade;
import com.levango7.dataenginebdp.encaps.security.facade.config.SecurityFacadeConfig;
import com.levango7.dataenginebdp.encaps.security.facade.crypto.CryptoFacade;
import com.levango7.dataenginebdp.encaps.security.facade.evidence.EvidenceArchive;
import com.levango7.dataenginebdp.encaps.security.facade.evidence.EvidenceCollector;
import com.levango7.dataenginebdp.encaps.security.facade.mask.MaskFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SecurityFacadeController} MockMvc 测试。
 *
 * <p>使用 standaloneSetup 方式，不依赖 Spring 上下文。</p>
 */
class SecurityFacadeControllerTest {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        SecurityFacadeConfig config = new SecurityFacadeConfig();
        config.getEvidence().setArchiveDir(tempDir.resolve("evidence").toString());
        config.getAssessment().setReportDir(tempDir.resolve("assessment").toString());

        CryptoSpiFactory spiFactory = new CryptoSpiFactory(new CryptoConfig());
        CryptoFacade cryptoFacade = new CryptoFacade(spiFactory, config);
        MaskFacade maskFacade = new MaskFacade(config);
        AuditFacade auditFacade = new AuditFacade(config);
        AuthFacade authFacade = new AuthFacade(config);
        EvidenceCollector collector = new EvidenceCollector(auditFacade, cryptoFacade, maskFacade, config);
        EvidenceArchive archive = new EvidenceArchive(config, objectMapper);
        AssessmentExporter exporter = new AssessmentExporter(config, objectMapper);

        SecurityFacade facade = new SecurityFacade(config, cryptoFacade, maskFacade,
                auditFacade, authFacade, collector, archive, exporter);
        SecurityFacadeController controller = new SecurityFacadeController(facade);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /api/v1/security/status — 返回 200")
    void status_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/security/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.crypto.enabled").value(true))
                .andExpect(jsonPath("$.mask.enabled").value(true))
                .andExpect(jsonPath("$.audit.enabled").value(true))
                .andExpect(jsonPath("$.auth.enabled").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/security/mask — 手机号脱敏")
    void mask_phone_shouldReturnMasked() throws Exception {
        String body = "{\"input\":\"13812345678\",\"type\":\"PHONE\"}";

        mockMvc.perform(post("/api/v1/security/mask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("PHONE"))
                .andExpect(jsonPath("$.masked").value("138****5678"));
    }

    @Test
    @DisplayName("POST /api/v1/security/mask — 邮箱脱敏")
    void mask_email_shouldReturnMasked() throws Exception {
        String body = "{\"input\":\"zhangsan@example.com\",\"type\":\"EMAIL\"}";

        mockMvc.perform(post("/api/v1/security/mask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.masked").value("z*******@example.com"));
    }

    @Test
    @DisplayName("GET /api/v1/security/audit/events — 空列表返回 200")
    void auditEvents_empty_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/security/audit/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/security/auth/check — 未认证返回 200 但 allowed=false")
    void authCheck_notAuthenticated_shouldReturn200WithDenied() throws Exception {
        mockMvc.perform(get("/api/v1/security/auth/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/security/evidence/collect — 收集并归档证据")
    void collectEvidence_shouldReturn200() throws Exception {
        mockMvc.perform(post("/api/v1/security/evidence/collect"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collected").exists())
                .andExpect(jsonPath("$.evidenceIds").exists());
    }

    @Test
    @DisplayName("POST /api/v1/security/assessment/export — 导出等保报告")
    void exportAssessment_dengbao_shouldReturn200() throws Exception {
        String body = "{\"type\":\"dengbao-2.0\",\"systemName\":\"测试系统\"}";

        mockMvc.perform(post("/api/v1/security/assessment/export")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("dengbao-2.0"))
                .andExpect(jsonPath("$.systemName").value("测试系统"))
                .andExpect(jsonPath("$.complianceRate").exists())
                .andExpect(jsonPath("$.filePath").exists());
    }

    @Test
    @DisplayName("POST /api/v1/security/assessment/export — 密评报告")
    void exportAssessment_miping_shouldReturn200() throws Exception {
        String body = "{\"type\":\"miping\",\"systemName\":\"测试系统\"}";

        mockMvc.perform(post("/api/v1/security/assessment/export")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("miping"));
    }

    @Test
    @DisplayName("POST /api/v1/security/assessment/export — 未知类型返回 400")
    void exportAssessment_unknownType_shouldReturn400() throws Exception {
        String body = "{\"type\":\"unknown\",\"systemName\":\"测试系统\"}";

        mockMvc.perform(post("/api/v1/security/assessment/export")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}