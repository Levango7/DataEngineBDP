package com.levango7.dataenginebdp.encaps.service;

import com.levango7.dataenginebdp.encaps.model.Tenant;
import com.levango7.dataenginebdp.encaps.repository.TenantRepository;
import com.levango7.dataenginebdp.encaps.security.Decrypt;
import com.levango7.dataenginebdp.encaps.security.Encrypt;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 租户服务（基于 Spring Data JPA 持久化实现）。
 *
 * <p>使用 {@link TenantRepository} 将 Tenant 持久化到关系型数据库。
 * 开发环境默认使用 H2 内存/文件数据库，生产环境通过环境变量切换 PostgreSQL。
 * 重启服务后数据不丢失（H2 文件模式或 PostgreSQL）。</p>
 */
@Service
public class TenantService {

    private final TenantRepository tenantRepository;

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    /**
     * 创建租户。
     *
     * @param tenant 入参，id 由数据库生成，createdAt/updatedAt 由服务层填充
     * @return 已落地的租户对象（含 id 与时间戳）
     */
    @Encrypt
    public Tenant create(Tenant tenant) {
        LocalDateTime now = LocalDateTime.now();
        tenant.setId(null);
        tenant.setCreatedAt(now);
        tenant.setUpdatedAt(now);
        if (tenant.getStatus() == null) {
            tenant.setStatus("ACTIVE");
        }
        return tenantRepository.save(tenant);
    }

    /**
     * 列出所有租户。
     *
     * @return 全量租户列表（不会返回 null）
     */
    @Decrypt
    public List<Tenant> list() {
        return tenantRepository.findAll();
    }

    /**
     * 按 ID 获取单个租户。
     *
     * @param id 租户 ID
     * @return Optional 包装的租户对象
     */
    @Decrypt
    public Optional<Tenant> get(Long id) {
        return tenantRepository.findById(id);
    }

    /**
     * 按 ID 更新租户。仅覆盖非 null 字段语义由调用方决定，这里采用整体覆盖。
     *
     * @param id      租户 ID
     * @param tenant  新的租户字段
     * @return 更新后的租户；若 ID 不存在则返回 Optional.empty()
     */
    @Encrypt
    public Optional<Tenant> update(Long id, Tenant tenant) {
        if (!tenantRepository.existsById(id)) {
            return Optional.empty();
        }
        tenant.setId(id);
        // 保留原 createdAt，避免被覆盖
        Tenant existing = tenantRepository.findById(id).orElseThrow();
        tenant.setCreatedAt(existing.getCreatedAt());
        tenant.setUpdatedAt(LocalDateTime.now());
        return Optional.of(tenantRepository.save(tenant));
    }

    /**
     * 按 ID 删除租户。
     *
     * @param id 租户 ID
     * @return true 表示存在并已删除；false 表示 ID 不存在
     */
    public boolean delete(Long id) {
        if (!tenantRepository.existsById(id)) {
            return false;
        }
        tenantRepository.deleteById(id);
        return true;
    }
}
