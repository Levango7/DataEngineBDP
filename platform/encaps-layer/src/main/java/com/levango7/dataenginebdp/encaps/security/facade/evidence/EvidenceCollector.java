package com.levango7.dataenginebdp.encaps.security.facade.evidence;

import com.levango7.dataenginebdp.encaps.security.facade.audit.AuditEvent;
import com.levango7.dataenginebdp.encaps.security.facade.audit.AuditFacade;
import com.levango7.dataenginebdp.encaps.security.facade.config.SecurityFacadeConfig;
import com.levango7.dataenginebdp.encaps.security.facade.crypto.CryptoFacade;
import com.levango7.dataenginebdp.encaps.security.facade.mask.MaskFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 证据收集器（EvidenceCollector）。
 *
 * <p>从各子 Facade（AuditFacade / CryptoFacade / MaskFacade）收集合规证据，
 * 包装为 {@link EvidenceItem}，供 {@link EvidenceArchive} 归档。</p>
 *
 * <h3>收集能力</h3>
 * <ul>
 *   <li>{@link #collectAuditLogs()} — 拉取当前 AuditFacade 缓冲的全部事件</li>
 *   <li>{@link #collectConfigSnapshot()} — 生成 SecurityFacade 配置快照</li>
 *   <li>{@link #collectCryptoOperation(String, String)} — 记录一次加解密操作</li>
 *   <li>{@link #collectMaskOperation(String, String, String)} — 记录一次脱敏操作</li>
 *   <li>{@link #collectAll()} — 一次性收集所有类型证据</li>
 * </ul>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>每条证据分配 UUID，便于归档后唯一检索</li>
 *   <li>内容为不可变 Map，避免归档后被篡改</li>
 *   <li>校验和由 {@link EvidenceArchive} 在落盘时计算并填充</li>
 * </ul>
 */
@Component
public class EvidenceCollector {

    private static final Logger log = LoggerFactory.getLogger(EvidenceCollector.class);

    private final AuditFacade auditFacade;
    private final CryptoFacade cryptoFacade;
    private final MaskFacade maskFacade;
    private final SecurityFacadeConfig config;

    /**
     * 构造 EvidenceCollector。
     *
     * @param auditFacade 审计门面
     * @param cryptoFacade 加解密门面
     * @param maskFacade   脱敏门面
     * @param config       SecurityFacade 配置
     */
    public EvidenceCollector(AuditFacade auditFacade,
                             CryptoFacade cryptoFacade,
                             MaskFacade maskFacade,
                             SecurityFacadeConfig config) {
        this.auditFacade = auditFacade;
        this.cryptoFacade = cryptoFacade;
        this.maskFacade = maskFacade;
        this.config = config;
    }

    /**
     * 收集审计日志证据。
     *
     * @return 证据项列表（每个 AuditEvent 一条）
     */
    public List<EvidenceItem> collectAuditLogs() {
        List<AuditEvent> events = auditFacade.list();
        List<EvidenceItem> items = new ArrayList<>(events.size());
        for (AuditEvent event : events) {
            Map<String, Object> content = new LinkedHashMap<>(event.toMap());
            items.add(new EvidenceItem(
                    UUID.randomUUID().toString(),
                    EvidenceType.AUDIT_LOG,
                    event.getTimestamp(),
                    "audit event: " + event.getAction(),
                    "AuditFacade",
                    content,
                    null));
        }
        log.info("Collected {} audit log evidence items", items.size());
        return items;
    }

    /**
     * 收集配置快照证据。
     *
     * @return 单条证据项
     */
    public EvidenceItem collectConfigSnapshot() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("facade.enabled", config.isEnabled());

        Map<String, Object> crypto = new LinkedHashMap<>();
        crypto.put("enabled", config.getCrypto().isEnabled());
        crypto.put("defaultProfile", config.getCrypto().getDefaultProfile());
        crypto.put("currentProvider", safeCurrentProvider());
        crypto.put("availableProviders", cryptoFacade.availableProviderNames());
        content.put("crypto", crypto);

        Map<String, Object> mask = new LinkedHashMap<>();
        mask.put("enabled", config.getMask().isEnabled());
        mask.put("builtInTypes", maskFacade.builtInTypes());
        mask.put("customRules", maskFacade.customRuleNames());
        content.put("mask", mask);

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("enabled", config.getAudit().isEnabled());
        audit.put("maxEventsRetained", config.getAudit().getMaxEventsRetained());
        audit.put("currentSize", auditFacade.size());
        content.put("audit", audit);

        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("enabled", config.getAuth().isEnabled());
        auth.put("requireTenant", config.getAuth().isRequireTenant());
        content.put("auth", auth);

        EvidenceItem item = new EvidenceItem(
                UUID.randomUUID().toString(),
                EvidenceType.CONFIG_SNAPSHOT,
                Instant.now(),
                "SecurityFacade configuration snapshot",
                "SecurityFacadeConfig",
                content,
                null);
        log.info("Collected config snapshot evidence: {}", item.getId());
        return item;
    }

    /**
     * 记录一次加解密操作证据。
     *
     * @param operation 操作名（ENCRYPT/DECRYPT/SIGN/VERIFY/DIGEST）
     * @param detail    操作详情
     * @return 证据项
     */
    public EvidenceItem collectCryptoOperation(String operation, String detail) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("operation", operation);
        content.put("detail", detail);
        content.put("provider", safeCurrentProvider());
        content.put("profile", cryptoFacade.currentProfile().name());

        EvidenceItem item = new EvidenceItem(
                UUID.randomUUID().toString(),
                EvidenceType.CRYPTO_OPERATION,
                Instant.now(),
                "crypto operation: " + operation,
                "CryptoFacade",
                content,
                null);
        log.debug("Collected crypto operation evidence: {}", item.getId());
        return item;
    }

    /**
     * 记录一次脱敏操作证据。
     *
     * @param type     脱敏类型
     * @param inputHash 输入摘要（避免泄露原文）
     * @param output   脱敏结果
     * @return 证据项
     */
    public EvidenceItem collectMaskOperation(String type, String inputHash, String output) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("maskType", type);
        content.put("inputHash", inputHash);
        content.put("output", output);

        EvidenceItem item = new EvidenceItem(
                UUID.randomUUID().toString(),
                EvidenceType.MASK_OPERATION,
                Instant.now(),
                "mask operation: " + type,
                "MaskFacade",
                content,
                null);
        log.debug("Collected mask operation evidence: {}", item.getId());
        return item;
    }

    /**
     * 一次性收集所有类型证据。
     *
     * @return 证据项列表
     */
    public List<EvidenceItem> collectAll() {
        List<EvidenceItem> all = new ArrayList<>();
        all.addAll(collectAuditLogs());
        all.add(collectConfigSnapshot());
        log.info("Collected total {} evidence items", all.size());
        return all;
    }

    private String safeCurrentProvider() {
        try {
            return cryptoFacade.currentProviderName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}