package com.levango7.dataenginebdp.ruleengine.service;

import com.levango7.dataenginebdp.ruleengine.model.Rule;
import com.levango7.dataenginebdp.ruleengine.repository.RuleRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 规则 CRUD 服务（基于 Spring Data JPA 持久化实现）。
 *
 * <p>使用 {@link RuleRepository} 将 Rule 持久化到关系型数据库。
 * 开发环境默认使用 H2 内存/文件数据库，生产环境通过环境变量切换 PostgreSQL。
 * 重启服务后数据不丢失（H2 文件模式或 PostgreSQL）。</p>
 *
 * <p>多租户隔离：{@code findByTenantId} / {@code getByIdAndTenantId} /
 * {@code update(id, rule, tenantId)} / {@code delete(id, tenantId)}
 * 供 {@link com.levango7.dataenginebdp.ruleengine.controller.QualityRuleController}
 * 按 tenantId 过滤，避免跨租户数据泄漏。原无租户重载保留以兼容
 * {@link com.levango7.dataenginebdp.ruleengine.controller.RuleController} 等旧调用方。</p>
 */
@Service
public class RuleService {

    private final RuleRepository ruleRepository;

    public RuleService(RuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    /** 创建规则 */
    public Rule create(Rule rule) {
        LocalDateTime now = LocalDateTime.now();
        rule.setId(null);
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        if (rule.getEnabled() == null) {
            rule.setEnabled(Boolean.TRUE);
        }
        return ruleRepository.save(rule);
    }

    /** 列出所有规则 */
    public List<Rule> listAll() {
        return ruleRepository.findAll();
    }

    /**
     * 列出指定租户下的所有规则（租户隔离）。
     *
     * @param tenantId 租户 ID（非 null）
     * @return 该租户下的所有规则
     */
    public List<Rule> findByTenantId(String tenantId) {
        return ruleRepository.findByTenantId(tenantId);
    }

    /** 根据 ID 获取规则 */
    public Rule getById(Long id) {
        if (id == null) {
            return null;
        }
        Optional<Rule> rule = ruleRepository.findById(id);
        return rule.orElse(null);
    }

    /**
     * 根据 ID 与租户 ID 联合获取规则（租户隔离的详情/更新/删除前置校验）。
     *
     * @param id       规则 ID
     * @param tenantId 租户 ID
     * @return 命中且属于该租户的规则；否则 {@code null}
     */
    public Rule getByIdAndTenantId(Long id, String tenantId) {
        if (id == null || tenantId == null) {
            return null;
        }
        return ruleRepository.findByIdAndTenantId(id, tenantId).orElse(null);
    }

    /** 更新规则 */
    public Rule update(Long id, Rule rule) {
        if (id == null) {
            return null;
        }
        if (!ruleRepository.existsById(id)) {
            return null;
        }
        rule.setId(id);
        // 保留原 createdAt，避免被覆盖
        Rule existing = ruleRepository.findById(id).orElseThrow();
        rule.setCreatedAt(existing.getCreatedAt());
        rule.setUpdatedAt(LocalDateTime.now());
        return ruleRepository.save(rule);
    }

    /**
     * 更新规则（租户隔离）。
     *
     * <p>先按 (id, tenantId) 校验存在性与租户归属，不匹配返回 {@code null}；
     * 命中则保留原 createdAt 与 tenantId，写入其余字段。</p>
     *
     * @param id       规则 ID
     * @param rule     待写入字段
     * @param tenantId 租户 ID
     * @return 更新后的规则；若不存在或不属于该租户则 {@code null}
     */
    public Rule update(Long id, Rule rule, String tenantId) {
        if (id == null || tenantId == null) {
            return null;
        }
        Optional<Rule> existingOpt = ruleRepository.findByIdAndTenantId(id, tenantId);
        if (existingOpt.isEmpty()) {
            return null;
        }
        Rule existing = existingOpt.get();
        rule.setId(id);
        rule.setCreatedAt(existing.getCreatedAt());
        // 锁定 tenantId，防止请求体篡改租户归属
        rule.setTenantId(tenantId);
        rule.setUpdatedAt(LocalDateTime.now());
        return ruleRepository.save(rule);
    }

    /** 删除规则 */
    public boolean delete(Long id) {
        if (id == null) {
            return false;
        }
        if (!ruleRepository.existsById(id)) {
            return false;
        }
        ruleRepository.deleteById(id);
        return true;
    }

    /**
     * 删除规则（租户隔离）。
     *
     * <p>先按 (id, tenantId) 校验存在性与租户归属，不匹配返回 {@code false}；
     * 命中则删除。</p>
     *
     * @param id       规则 ID
     * @param tenantId 租户 ID
     * @return 删除成功返回 {@code true}；不存在或不属于该租户返回 {@code false}
     */
    public boolean delete(Long id, String tenantId) {
        if (id == null || tenantId == null) {
            return false;
        }
        Optional<Rule> existing = ruleRepository.findByIdAndTenantId(id, tenantId);
        if (existing.isEmpty()) {
            return false;
        }
        ruleRepository.delete(existing.get());
        return true;
    }
}
