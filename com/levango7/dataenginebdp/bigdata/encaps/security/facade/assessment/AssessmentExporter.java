package com.shuqing.bigdata.encaps.security.facade.assessment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shuqing.bigdata.encaps.security.facade.config.SecurityFacadeConfig;
import com.shuqing.bigdata.encaps.security.facade.evidence.EvidenceItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 测评报告导出器（AssessmentExporter）。
 *
 * <p>根据当前安全能力实施情况与已归档证据，生成等保 2.0 / 密评测评报告，
 * 包含控制项状态、证据清单、差距分析。</p>
 *
 * <h3>导出能力</h3>
 * <ul>
 *   <li>{@link #export(AssessmentType, List, String)} — 导出指定标准报告到文件</li>
 *   <li>{@link #generate(AssessmentType, List, String)} — 仅生成报告对象（不落盘）</li>
 *   <li>内置等保 2.0 与密评控制项模板</li>
 * </ul>
 *
 * <h3>报告结构</h3>
 * <ul>
 *   <li>报告元数据（ID / 类型 / 时间 / 系统名 / 合规率）</li>
 *   <li>控制项状态列表（每项含编号 / 名称 / 要求 / 状态 / 关联证据 / 差距 / 整改建议）</li>
 *   <li>证据清单（证据 ID 列表）</li>
 *   <li>总结（合规率与主要差距）</li>
 * </ul>
 */
@Component
public class AssessmentExporter {

    private static final Logger log = LoggerFactory.getLogger(AssessmentExporter.class);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final SecurityFacadeConfig config;
    private final ObjectMapper objectMapper;

    /**
     * 构造 AssessmentExporter。
     *
     * @param config       SecurityFacade 配置
     * @param objectMapper Jackson ObjectMapper
     */
    public AssessmentExporter(SecurityFacadeConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成测评报告（不落盘）。
     *
     * @param type        测评类型
     * @param evidence    已归档证据列表
     * @param systemName  系统名称
     * @return AssessmentReport
     */
    public AssessmentReport generate(AssessmentType type, List<EvidenceItem> evidence, String systemName) {
        List<ControlItemStatus> controls = evaluateControlItems(type, evidence);
        List<String> evidenceIds = evidence.stream().map(EvidenceItem::getId).toList();

        // 生成总结
        long compliant = controls.stream().filter(c -> c.getStatus() == ComplianceStatus.COMPLIANT).count();
        long partial = controls.stream().filter(c -> c.getStatus() == ComplianceStatus.PARTIAL).count();
        long nonCompliant = controls.stream().filter(c -> c.getStatus() == ComplianceStatus.NON_COMPLIANT).count();
        long na = controls.stream().filter(c -> c.getStatus() == ComplianceStatus.NOT_APPLICABLE).count();
        String summary = String.format(
                "控制项总数=%d, 符合=%d, 部分符合=%d, 不符合=%d, 不适用=%d, 合规率=%.2f%%",
                controls.size(), compliant, partial, nonCompliant, na,
                (compliant + na) * 100.0 / Math.max(1, controls.size()));

        AssessmentReport report = new AssessmentReport(
                UUID.randomUUID().toString(),
                type,
                Instant.now(),
                systemName,
                controls,
                evidenceIds,
                summary);

        log.info("Generated assessment report: {}", report);
        return report;
    }

    /**
     * 导出测评报告到文件。
     *
     * @param type       测评类型
     * @param evidence   已归档证据列表
     * @param systemName 系统名称
     * @return 报告文件路径
     * @throws IOException 写入失败
     */
    public Path export(AssessmentType type, List<EvidenceItem> evidence, String systemName) throws IOException {
        AssessmentReport report = generate(type, evidence, systemName);

        Path reportDir = config.getAssessment().getReportPath();
        Files.createDirectories(reportDir);

        String timestamp = report.getGeneratedAt().atZone(java.time.ZoneOffset.UTC).toLocalDateTime().format(TS_FMT);
        String filename = type.getCode() + "-" + timestamp + "-" + report.getId().substring(0, 8) + ".json";
        Path target = reportDir.resolve(filename);

        byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report.toMap());

        // 原子写入
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, json,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        log.info("Exported assessment report to {}", target);
        return target;
    }

    /**
     * 使用配置中的默认测评标准导出。
     *
     * @param evidence   证据列表
     * @param systemName 系统名称
     * @return 报告文件路径
     * @throws IOException 写入失败
     */
    public Path exportWithDefaultStandard(List<EvidenceItem> evidence, String systemName) throws IOException {
        AssessmentType type = AssessmentType.fromCode(config.getAssessment().getDefaultStandard());
        return export(type, evidence, systemName);
    }

    // ===== 控制项评估 =====

    /**
     * 根据测评类型与证据评估各控制项状态。
     *
     * <p>当前实现基于证据类型存在性进行启发式评估：
     * 若存在对应类型的证据，则视为 COMPLIANT；否则 PARTIAL（部分实施）。</p>
     *
     * @param type     测评类型
     * @param evidence 证据列表
     * @return 控制项状态列表
     */
    private List<ControlItemStatus> evaluateControlItems(AssessmentType type, List<EvidenceItem> evidence) {
        List<ControlItemStatus> controls = new ArrayList<>();
        List<String> allEvidenceIds = evidence.stream().map(EvidenceItem::getId).toList();

        // 证据类型计数
        long auditCount = evidence.stream()
                .filter(e -> e.getType() == com.shuqing.bigdata.encaps.security.facade.evidence.EvidenceType.AUDIT_LOG)
                .count();
        long configCount = evidence.stream()
                .filter(e -> e.getType() == com.shuqing.bigdata.encaps.security.facade.evidence.EvidenceType.CONFIG_SNAPSHOT)
                .count();
        long cryptoCount = evidence.stream()
                .filter(e -> e.getType() == com.shuqing.bigdata.encaps.security.facade.evidence.EvidenceType.CRYPTO_OPERATION)
                .count();

        switch (type) {
            case DENGBAO_2_0 -> evaluateDengbao20(controls, allEvidenceIds, auditCount, configCount, cryptoCount);
            case MIPING -> evaluateMiping(controls, allEvidenceIds, cryptoCount, configCount);
            case PIPIA -> evaluatePipia(controls, allEvidenceIds, auditCount);
        }

        return controls;
    }

    /**
     * 等保 2.0 控制项评估。
     */
    private void evaluateDengbao20(List<ControlItemStatus> controls, List<String> evidenceIds,
                                   long auditCount, long configCount, long cryptoCount) {
        // 8.1.4.1 身份鉴别
        controls.add(new ControlItemStatus(
                "8.1.4.1", "身份鉴别",
                "应对登录的用户进行身份标识和鉴别，标识具有唯一性",
                auditCount > 0 ? ComplianceStatus.COMPLIANT : ComplianceStatus.PARTIAL,
                evidenceIds,
                auditCount == 0 ? "未收集到鉴权相关审计事件" : null,
                auditCount == 0 ? "启用 AuthFacade 并记录鉴权事件" : null));

        // 8.1.4.3 安全审计
        controls.add(new ControlItemStatus(
                "8.1.4.3", "安全审计",
                "启用安全审计功能，覆盖每个用户，对重要的安全事件进行审计",
                auditCount > 0 ? ComplianceStatus.COMPLIANT : ComplianceStatus.NON_COMPLIANT,
                evidenceIds,
                auditCount == 0 ? "未发现审计事件记录" : null,
                auditCount == 0 ? "启用 AuditFacade 并配置审计策略" : null));

        // 8.1.4.4 数据完整性
        controls.add(new ControlItemStatus(
                "8.1.4.4", "数据完整性",
                "采用密码技术保证重要数据在存储过程中的完整性",
                cryptoCount > 0 ? ComplianceStatus.COMPLIANT : ComplianceStatus.PARTIAL,
                evidenceIds,
                cryptoCount == 0 ? "未收集到加解密操作证据" : null,
                cryptoCount == 0 ? "对重要数据启用签名/摘要保护" : null));

        // 8.1.4.5 数据保密性
        controls.add(new ControlItemStatus(
                "8.1.4.5", "数据保密性",
                "采用密码技术保证重要数据在存储过程中的保密性",
                cryptoCount > 0 ? ComplianceStatus.COMPLIANT : ComplianceStatus.PARTIAL,
                evidenceIds,
                cryptoCount == 0 ? "未收集到加密操作证据" : null,
                cryptoCount == 0 ? "对敏感数据启用加密存储" : null));

        // 配置管理
        controls.add(new ControlItemStatus(
                "8.1.5.1", "安全配置",
                "应保存和定期更新安全配置基线",
                configCount > 0 ? ComplianceStatus.COMPLIANT : ComplianceStatus.PARTIAL,
                evidenceIds,
                configCount == 0 ? "未生成配置快照" : null,
                configCount == 0 ? "启用 EvidenceCollector.collectConfigSnapshot" : null));
    }

    /**
     * 密评控制项评估（GM/T 0054）。
     */
    private void evaluateMiping(List<ControlItemStatus> controls, List<String> evidenceIds,
                                long cryptoCount, long configCount) {
        // 密码算法合规性
        controls.add(new ControlItemStatus(
                "4.1", "密码算法合规性",
                "应使用国家密码管理部门批准的密码算法",
                cryptoCount > 0 ? ComplianceStatus.COMPLIANT : ComplianceStatus.NON_COMPLIANT,
                evidenceIds,
                cryptoCount == 0 ? "未发现密码算法使用记录" : null,
                cryptoCount == 0 ? "启用 CryptoFacade 并使用国密算法" : null));

        // 密钥管理
        controls.add(new ControlItemStatus(
                "4.2", "密钥管理",
                "应对密钥的生成、存储、分发、使用、销毁进行管理",
                configCount > 0 ? ComplianceStatus.PARTIAL : ComplianceStatus.NON_COMPLIANT,
                evidenceIds,
                "密钥管理流程需独立 KMS 支持，当前仅记录配置快照",
                "接入独立 KMS 系统管理密钥生命周期"));

        // 数据机密性
        controls.add(new ControlItemStatus(
                "4.3", "数据机密性",
                "应使用密码技术保证数据机密性",
                cryptoCount > 0 ? ComplianceStatus.COMPLIANT : ComplianceStatus.NON_COMPLIANT,
                evidenceIds,
                cryptoCount == 0 ? "未发现加密操作" : null,
                cryptoCount == 0 ? "对敏感数据启用 SM4/AES 加密" : null));

        // 数据完整性
        controls.add(new ControlItemStatus(
                "4.4", "数据完整性",
                "应使用密码技术保证数据完整性",
                cryptoCount > 0 ? ComplianceStatus.COMPLIANT : ComplianceStatus.NON_COMPLIANT,
                evidenceIds,
                cryptoCount == 0 ? "未发现签名/摘要操作" : null,
                cryptoCount == 0 ? "对重要数据启用 SM2/SM3 签名" : null));
    }

    /**
     * PIPIA 控制项评估。
     */
    private void evaluatePipia(List<ControlItemStatus> controls, List<String> evidenceIds, long auditCount) {
        controls.add(new ControlItemStatus(
                "PIPL-9", "个人信息处理记录",
                "处理个人信息应留存操作记录",
                auditCount > 0 ? ComplianceStatus.COMPLIANT : ComplianceStatus.PARTIAL,
                evidenceIds,
                auditCount == 0 ? "未收集到个人信息处理审计事件" : null,
                auditCount == 0 ? "对个人信息处理操作启用审计" : null));

        controls.add(new ControlItemStatus(
                "PIPL-51", "个人信息去标识化",
                "处理个人信息应采取去标识化等技术措施",
                ComplianceStatus.COMPLIANT,
                evidenceIds,
                null,
                null));
    }
}