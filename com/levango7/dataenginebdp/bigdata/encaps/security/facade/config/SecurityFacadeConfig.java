package com.shuqing.bigdata.encaps.security.facade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * SecurityFacade 统一安全封装配置。
 *
 * <p>绑定 {@code app.security.facade.*} 配置项，控制四大能力（加解密/脱敏/审计/鉴权）
 * 的开关、证据归档路径、测评报告模板等。</p>
 *
 * <h3>典型 YAML</h3>
 * <pre>{@code
 * app:
 *   security:
 *     facade:
 *       enabled: true
 *       crypto:
 *         enabled: true
 *         default-profile: xinchang
 *       mask:
 *         enabled: true
 *         default-replacement: "*"
 *       audit:
 *         enabled: true
 *         max-events-retained: 10000
 *       auth:
 *         enabled: true
 *         require-tenant: true
 *       evidence:
 *         archive-dir: ./data/security-evidence
 *         snapshot-on-startup: true
 *       assessment:
 *         report-dir: ./data/security-assessment
 *         default-standard: dengbao-2.0
 * }</pre>
 *
 * <p>设计原则：所有子能力均默认启用（fail-open 便于开发），生产环境通过
 * 环境变量按需关闭；归档与报告目录默认落在 {@code ./data/} 下，与 H2 数据库目录对齐。</p>
 */
@Configuration
@ConfigurationProperties(prefix = "app.security.facade")
public class SecurityFacadeConfig {

    /** 总开关，关闭后 SecurityFacade 仍可实例化但所有操作抛 IllegalStateException */
    private boolean enabled = true;

    /** 加解密子配置 */
    private Crypto crypto = new Crypto();

    /** 脱敏子配置 */
    private Mask mask = new Mask();

    /** 审计子配置 */
    private Audit audit = new Audit();

    /** 鉴权子配置 */
    private Auth auth = new Auth();

    /** 证据归档子配置 */
    private Evidence evidence = new Evidence();

    /** 测评导出子配置 */
    private Assessment assessment = new Assessment();

    // ===== nested config classes =====

    /**
     * 加解密子配置。
     */
    public static class Crypto {
        /** 是否启用加解密能力 */
        private boolean enabled = true;

        /** 默认 Profile，未指定时回退到 CryptoConfig.activeProfile */
        private String defaultProfile;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDefaultProfile() { return defaultProfile; }
        public void setDefaultProfile(String defaultProfile) { this.defaultProfile = defaultProfile; }
    }

    /**
     * 脱敏子配置。
     */
    public static class Mask {
        /** 是否启用脱敏能力 */
        private boolean enabled = true;

        /** 默认替换字符 */
        private String defaultReplacement = "*";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDefaultReplacement() { return defaultReplacement; }
        public void setDefaultReplacement(String defaultReplacement) { this.defaultReplacement = defaultReplacement; }
    }

    /**
     * 审计子配置。
     */
    public static class Audit {
        /** 是否启用审计能力 */
        private boolean enabled = true;

        /** 内存中保留的最大事件数（环形缓冲） */
        private int maxEventsRetained = 10000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxEventsRetained() { return maxEventsRetained; }
        public void setMaxEventsRetained(int maxEventsRetained) { this.maxEventsRetained = maxEventsRetained; }
    }

    /**
     * 鉴权子配置。
     */
    public static class Auth {
        /** 是否启用鉴权能力 */
        private boolean enabled = true;

        /** 是否强制要求租户上下文 */
        private boolean requireTenant = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isRequireTenant() { return requireTenant; }
        public void setRequireTenant(boolean requireTenant) { this.requireTenant = requireTenant; }
    }

    /**
     * 证据归档子配置。
     */
    public static class Evidence {
        /** 归档目录 */
        private String archiveDir = "./data/security-evidence";

        /** 启动时是否生成配置快照 */
        private boolean snapshotOnStartup = true;

        public String getArchiveDir() { return archiveDir; }
        public void setArchiveDir(String archiveDir) { this.archiveDir = archiveDir; }
        public Path getArchivePath() { return Paths.get(archiveDir); }
        public boolean isSnapshotOnStartup() { return snapshotOnStartup; }
        public void setSnapshotOnStartup(boolean snapshotOnStartup) { this.snapshotOnStartup = snapshotOnStartup; }
    }

    /**
     * 测评导出子配置。
     */
    public static class Assessment {
        /** 报告输出目录 */
        private String reportDir = "./data/security-assessment";

        /** 默认测评标准（等保 2.0 / 密评） */
        private String defaultStandard = "dengbao-2.0";

        public String getReportDir() { return reportDir; }
        public void setReportDir(String reportDir) { this.reportDir = reportDir; }
        public Path getReportPath() { return Paths.get(reportDir); }
        public String getDefaultStandard() { return defaultStandard; }
        public void setDefaultStandard(String defaultStandard) { this.defaultStandard = defaultStandard; }
    }

    // ===== getters / setters =====

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Crypto getCrypto() { return crypto; }
    public void setCrypto(Crypto crypto) { this.crypto = crypto; }

    public Mask getMask() { return mask; }
    public void setMask(Mask mask) { this.mask = mask; }

    public Audit getAudit() { return audit; }
    public void setAudit(Audit audit) { this.audit = audit; }

    public Auth getAuth() { return auth; }
    public void setAuth(Auth auth) { this.auth = auth; }

    public Evidence getEvidence() { return evidence; }
    public void setEvidence(Evidence evidence) { this.evidence = evidence; }

    public Assessment getAssessment() { return assessment; }
    public void setAssessment(Assessment assessment) { this.assessment = assessment; }
}