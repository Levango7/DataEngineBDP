package com.shuqing.bigdata.encaps.security.facade.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SecurityFacadeConfig} 单元测试。
 */
class SecurityFacadeConfigTest {

    @Test
    @DisplayName("默认配置 — 所有能力启用")
    void defaults_allEnabled() {
        SecurityFacadeConfig config = new SecurityFacadeConfig();

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getCrypto().isEnabled()).isTrue();
        assertThat(config.getMask().isEnabled()).isTrue();
        assertThat(config.getAudit().isEnabled()).isTrue();
        assertThat(config.getAuth().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("默认配置 — 脱敏替换字符为 *")
    void defaultMaskReplacement_isAsterisk() {
        SecurityFacadeConfig config = new SecurityFacadeConfig();
        assertThat(config.getMask().getDefaultReplacement()).isEqualTo("*");
    }

    @Test
    @DisplayName("默认配置 — 审计缓冲上限 10000")
    void defaultAuditMaxRetained_10000() {
        SecurityFacadeConfig config = new SecurityFacadeConfig();
        assertThat(config.getAudit().getMaxEventsRetained()).isEqualTo(10000);
    }

    @Test
    @DisplayName("默认配置 — 鉴权要求租户")
    void defaultAuthRequireTenant_true() {
        SecurityFacadeConfig config = new SecurityFacadeConfig();
        assertThat(config.getAuth().isRequireTenant()).isTrue();
    }

    @Test
    @DisplayName("默认配置 — 证据归档目录")
    void defaultEvidenceArchiveDir() {
        SecurityFacadeConfig config = new SecurityFacadeConfig();
        assertThat(config.getEvidence().getArchiveDir()).isEqualTo("./data/security-evidence");
        assertThat(config.getEvidence().getArchivePath().toString()).contains("security-evidence");
    }

    @Test
    @DisplayName("默认配置 — 测评标准 dengbao-2.0")
    void defaultAssessmentStandard() {
        SecurityFacadeConfig config = new SecurityFacadeConfig();
        assertThat(config.getAssessment().getDefaultStandard()).isEqualTo("dengbao-2.0");
    }

    @Test
    @DisplayName("设置属性 — 可正确读写")
    void setProperties_shouldWork() {
        SecurityFacadeConfig config = new SecurityFacadeConfig();
        config.setEnabled(false);
        config.getCrypto().setDefaultProfile("international");
        config.getAudit().setMaxEventsRetained(5000);
        config.getEvidence().setArchiveDir("/tmp/evidence");
        config.getAssessment().setDefaultStandard("miping");

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getCrypto().getDefaultProfile()).isEqualTo("international");
        assertThat(config.getAudit().getMaxEventsRetained()).isEqualTo(5000);
        assertThat(config.getEvidence().getArchiveDir()).isEqualTo("/tmp/evidence");
        assertThat(config.getAssessment().getDefaultStandard()).isEqualTo("miping");
    }
}