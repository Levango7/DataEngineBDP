package com.levango7.dataenginebdp.finops.billing.repository;

import com.levango7.dataenginebdp.finops.billing.model.BillingModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账单仓库租户隔离集成测试。
 *
 * <p>使用 @DataJpaTest + H2 内存数据库，直接验证 {@link BillingRepository#findByIdAndTenantId}
 * 实现租户隔离：租户 A 无法通过账单 ID 查询到租户 B 的账单。</p>
 *
 * <p>对应 P0 漏洞修复：BillingController.getById 租户隔离校验。</p>
 */
@DataJpaTest
class BillingRepositoryTenantIsolationTest {

    @Autowired
    private BillingRepository billingRepository;

    /** 构造一个最小合法账单模型（id 由 UUID 策略生成，其余字段必填）。 */
    private BillingModel newBill(String tenantId, String period) {
        return BillingModel.builder()
                .tenantId(tenantId)
                .billingPeriod(period)
                .itemsJson("[]")
                .totalAmount(BigDecimal.ZERO)
                .status("GENERATED")
                .generatedAt(Instant.now())
                .periodStart(Instant.now())
                .periodEnd(Instant.now())
                .build();
    }

    @Test
    void findByIdAndTenantId_returnsBill_whenBelongsToCurrentTenant() {
        BillingModel saved = billingRepository.save(newBill("tenant-A", "2026-08"));

        Optional<BillingModel> found = billingRepository.findByIdAndTenantId(saved.getId(), "tenant-A");

        assertThat(found).isPresent();
        assertThat(found.get().getTenantId()).isEqualTo("tenant-A");
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    void findByIdAndTenantId_returnsEmpty_whenBillBelongsToOtherTenant() {
        // tenant-B 的账单
        BillingModel billOfB = billingRepository.save(newBill("tenant-B", "2026-08"));

        // tenant-A 试图通过 ID 访问 tenant-B 的账单 → 必须返回 empty
        Optional<BillingModel> found = billingRepository.findByIdAndTenantId(billOfB.getId(), "tenant-A");

        assertThat(found).isEmpty();
    }

    @Test
    void findByIdAndTenantId_returnsEmpty_whenBillNotExists() {
        Optional<BillingModel> found = billingRepository.findByIdAndTenantId("nonexistent-bill-id", "tenant-A");

        assertThat(found).isEmpty();
    }

    @Test
    void crossTenantIsolation_twoTenantsCannotSeeEachOtherBills() {
        BillingModel billOfA = billingRepository.save(newBill("tenant-A", "2026-08"));
        BillingModel billOfB = billingRepository.save(newBill("tenant-B", "2026-08"));

        // 各自能查到自己的账单
        assertThat(billingRepository.findByIdAndTenantId(billOfA.getId(), "tenant-A")).isPresent();
        assertThat(billingRepository.findByIdAndTenantId(billOfB.getId(), "tenant-B")).isPresent();

        // 互相查不到对方的账单（租户隔离核心断言）
        assertThat(billingRepository.findByIdAndTenantId(billOfA.getId(), "tenant-B")).isEmpty();
        assertThat(billingRepository.findByIdAndTenantId(billOfB.getId(), "tenant-A")).isEmpty();
    }
}