package com.levango7.dataenginebdp.encaps.security.facade.evidence;

import com.levango7.dataenginebdp.encaps.crypto.CryptoConfig;
import com.levango7.dataenginebdp.encaps.crypto.CryptoSpiFactory;
import com.levango7.dataenginebdp.encaps.security.facade.audit.AuditFacade;
import com.levango7.dataenginebdp.encaps.security.facade.audit.AuditLevel;
import com.levango7.dataenginebdp.encaps.security.facade.config.SecurityFacadeConfig;
import com.levango7.dataenginebdp.encaps.security.facade.crypto.CryptoFacade;
import com.levango7.dataenginebdp.encaps.security.facade.mask.MaskFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EvidenceCollector} 单元测试。
 */
class EvidenceCollectorTest {

    private EvidenceCollector collector;
    private AuditFacade auditFacade;

    @BeforeEach
    void setUp() {
        SecurityFacadeConfig config = new SecurityFacadeConfig();
        auditFacade = new AuditFacade(config);
        CryptoFacade cryptoFacade = new CryptoFacade(
                new CryptoSpiFactory(new CryptoConfig()), config);
        MaskFacade maskFacade = new MaskFacade(config);
        collector = new EvidenceCollector(auditFacade, cryptoFacade, maskFacade, config);
    }

    @Test
    @DisplayName("collectAuditLogs — 空审计时返回空列表")
    void collectAuditLogs_empty_shouldReturnEmptyList() {
        List<EvidenceItem> items = collector.collectAuditLogs();
        assertThat(items).isEmpty();
    }

    @Test
    @DisplayName("collectAuditLogs — 每个事件生成一条证据")
    void collectAuditLogs_shouldGenerateOnePerEvent() {
        auditFacade.record("LOGIN").tenantId("t1").userId("u1").build();
        auditFacade.record("LOGOUT").tenantId("t1").userId("u1").build();

        List<EvidenceItem> items = collector.collectAuditLogs();

        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(item -> {
            assertThat(item.getType()).isEqualTo(EvidenceType.AUDIT_LOG);
            assertThat(item.getSource()).isEqualTo("AuditFacade");
            assertThat(item.getId()).isNotBlank();
            assertThat(item.getChecksum()).isNull(); // 归档时才填充
        });
    }

    @Test
    @DisplayName("collectConfigSnapshot — 返回配置快照证据")
    void collectConfigSnapshot_shouldReturnSnapshot() {
        EvidenceItem item = collector.collectConfigSnapshot();

        assertThat(item.getType()).isEqualTo(EvidenceType.CONFIG_SNAPSHOT);
        assertThat(item.getSource()).isEqualTo("SecurityFacadeConfig");
        assertThat(item.getContent()).containsKeys("facade.enabled", "crypto", "mask", "audit", "auth");
        assertThat(item.getContent().get("facade.enabled")).isEqualTo(true);
    }

    @Test
    @DisplayName("collectCryptoOperation — 记录加解密操作证据")
    void collectCryptoOperation_shouldRecordEvidence() {
        EvidenceItem item = collector.collectCryptoOperation("ENCRYPT", "encrypted 100 bytes");

        assertThat(item.getType()).isEqualTo(EvidenceType.CRYPTO_OPERATION);
        assertThat(item.getContent()).containsEntry("operation", "ENCRYPT");
        assertThat(item.getContent()).containsEntry("detail", "encrypted 100 bytes");
    }

    @Test
    @DisplayName("collectMaskOperation — 记录脱敏操作证据")
    void collectMaskOperation_shouldRecordEvidence() {
        EvidenceItem item = collector.collectMaskOperation("PHONE", "abc123", "138****5678");

        assertThat(item.getType()).isEqualTo(EvidenceType.MASK_OPERATION);
        assertThat(item.getContent()).containsEntry("maskType", "PHONE");
        assertThat(item.getContent()).containsEntry("output", "138****5678");
    }

    @Test
    @DisplayName("collectAll — 收集审计日志 + 配置快照")
    void collectAll_shouldCollectAll() {
        auditFacade.record("TEST").level(AuditLevel.INFO).build();

        List<EvidenceItem> all = collector.collectAll();

        // 1 条审计 + 1 条配置快照
        assertThat(all).hasSize(2);
        assertThat(all).extracting(EvidenceItem::getType)
                .contains(EvidenceType.AUDIT_LOG, EvidenceType.CONFIG_SNAPSHOT);
    }
}