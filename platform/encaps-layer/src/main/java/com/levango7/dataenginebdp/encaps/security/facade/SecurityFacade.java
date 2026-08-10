package com.levango7.dataenginebdp.encaps.security.facade;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * SecurityFacade — 安全能力统一入口。
 *
 * <p>聚合四大安全能力（加解密/脱敏/审计/鉴权）与两大合规能力（证据归档/测评导出），
 * 对外提供单一入口，屏蔽底层实现差异。</p>
 *
 * <h3>架构</h3>
 * <pre>{@code
 * SecurityFacade
 *   ├── CryptoFacade       — 加解密（委托 T022 CryptoSpiFactory）
 *   ├── MaskFacade         — 脱敏（内置 8 种规则 + 自定义）
 *   ├── AuditFacade        — 审计（环形缓冲 + SLF4J 落盘）
 *   ├── AuthFacade         — 鉴权（委托 Spring Security + TenantContext）
 *   ├── EvidenceCollector  — 证据收集
 *   ├── EvidenceArchive    — 证据归档（文件 + SHA-256 校验和）
 *   └── AssessmentExporter — 测评导出（等保 2.0 / 密评 / PIPIA）
 * }</pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 加密
 * String cipher = facade.crypto().encrypt(plaintext, key);
 *
 * // 脱敏
 * String masked = facade.mask().mask("13812345678", MaskType.PHONE);
 *
 * // 审计
 * facade.audit().record("LOGIN").tenantId("t1").userId("u1").build();
 *
 * // 鉴权
 * AuthResult result = facade.auth().checkFullAccess();
 *
 * // 收集并归档证据
 * List<EvidenceItem> evidence = facade.collectEvidence();
 * facade.archiveEvidence(evidence);
 *
 * // 导出等保测评报告
 * Path report = facade.exportAssessment(AssessmentType.DENGBAO_2_0, evidence, "数据引擎大数据平台");
 * }</pre>
 *
 * <h3>线程安全</h3>
 * <p>本类无状态，所有子 Facade 均线程安全，可作为 Spring 单例被并发调用。</p>
 */
@Component
public class SecurityFacade {

    private static final Logger log = LoggerFactory.getLogger(SecurityFacade.class);

    private final SecurityFacadeConfig config;
    private final CryptoFacade cryptoFacade;
    private final MaskFacade maskFacade;
    private final AuditFacade auditFacade;
    private final AuthFacade authFacade;
    private final EvidenceCollector evidenceCollector;
    private final EvidenceArchive evidenceArchive;
    private final AssessmentExporter assessmentExporter;

    /**
     * 构造 SecurityFacade，注入所有子能力。
     *
     * @param config             配置
     * @param cryptoFacade       加解密门面
     * @param maskFacade         脱敏门面
     * @param auditFacade        审计门面
     * @param authFacade         鉴权门面
     * @param evidenceCollector  证据收集器
     * @param evidenceArchive    证据归档器
     * @param assessmentExporter 测评导出器
     */
    public SecurityFacade(SecurityFacadeConfig config,
                          CryptoFacade cryptoFacade,
                          MaskFacade maskFacade,
                          AuditFacade auditFacade,
                          AuthFacade authFacade,
                          EvidenceCollector evidenceCollector,
                          EvidenceArchive evidenceArchive,
                          AssessmentExporter assessmentExporter) {
        this.config = config;
        this.cryptoFacade = cryptoFacade;
        this.maskFacade = maskFacade;
        this.auditFacade = auditFacade;
        this.authFacade = authFacade;
        this.evidenceCollector = evidenceCollector;
        this.evidenceArchive = evidenceArchive;
        this.assessmentExporter = assessmentExporter;
        log.info("SecurityFacade initialized: enabled={}, crypto={}, mask={}, audit={}, auth={}",
                config.isEnabled(),
                config.getCrypto().isEnabled(),
                config.getMask().isEnabled(),
                config.getAudit().isEnabled(),
                config.getAuth().isEnabled());
    }

    // ===== 子能力访问器 =====

    /**
     * 加解密能力。
     *
     * @return CryptoFacade
     */
    public CryptoFacade crypto() {
        return cryptoFacade;
    }

    /**
     * 脱敏能力。
     *
     * @return MaskFacade
     */
    public MaskFacade mask() {
        return maskFacade;
    }

    /**
     * 审计能力。
     *
     * @return AuditFacade
     */
    public AuditFacade audit() {
        return auditFacade;
    }

    /**
     * 鉴权能力。
     *
     * @return AuthFacade
     */
    public AuthFacade auth() {
        return authFacade;
    }

    // ===== 证据收集与归档 =====

    /**
     * 收集当前所有合规证据（审计日志 + 配置快照）。
     *
     * @return 证据项列表
     */
    public List<EvidenceItem> collectEvidence() {
        ensureEnabled();
        return evidenceCollector.collectAll();
    }

    /**
     * 归档证据列表。
     *
     * @param items 证据项列表
     * @return 归档后的证据项列表（含校验和）
     * @throws IOException 归档失败
     */
    public List<EvidenceItem> archiveEvidence(List<EvidenceItem> items) throws IOException {
        ensureEnabled();
        return evidenceArchive.archiveAll(items);
    }

    /**
     * 一键收集并归档所有证据。
     *
     * @return 归档后的证据项列表
     * @throws IOException 归档失败
     */
    public List<EvidenceItem> collectAndArchiveEvidence() throws IOException {
        ensureEnabled();
        List<EvidenceItem> collected = evidenceCollector.collectAll();
        return evidenceArchive.archiveAll(collected);
    }

    // ===== 测评导出 =====

    /**
     * 生成测评报告（不落盘）。
     *
     * @param type       测评类型
     * @param evidence   证据列表
     * @param systemName 系统名称
     * @return AssessmentReport
     */
    public AssessmentReport generateAssessment(AssessmentType type,
                                               List<EvidenceItem> evidence,
                                               String systemName) {
        ensureEnabled();
        return assessmentExporter.generate(type, evidence, systemName);
    }

    /**
     * 导出测评报告到文件。
     *
     * @param type       测评类型
     * @param evidence   证据列表
     * @param systemName 系统名称
     * @return 报告文件路径
     * @throws IOException 写入失败
     */
    public Path exportAssessment(AssessmentType type,
                                 List<EvidenceItem> evidence,
                                 String systemName) throws IOException {
        ensureEnabled();
        return assessmentExporter.export(type, evidence, systemName);
    }

    /**
     * 一键收集证据并导出测评报告。
     *
     * @param type       测评类型
     * @param systemName 系统名称
     * @return 报告文件路径
     * @throws IOException 写入失败
     */
    public Path collectAndExportAssessment(AssessmentType type, String systemName) throws IOException {
        ensureEnabled();
        List<EvidenceItem> evidence = evidenceCollector.collectAll();
        return assessmentExporter.export(type, evidence, systemName);
    }

    // ===== 状态查询 =====

    /**
     * SecurityFacade 是否启用。
     *
     * @return true 表示启用
     */
    public boolean isEnabled() {
        return config.isEnabled();
    }

    /**
     * 获取配置。
     *
     * @return 配置对象
     */
    public SecurityFacadeConfig getConfig() {
        return config;
    }

    private void ensureEnabled() {
        if (!config.isEnabled()) {
            throw new IllegalStateException("SecurityFacade is disabled (app.security.facade.enabled=false)");
        }
    }
}