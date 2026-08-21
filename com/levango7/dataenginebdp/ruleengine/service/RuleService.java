package com.shuqing.bigdata.ruleengine.service;

import com.shuqing.bigdata.ruleengine.model.Rule;
import com.shuqing.bigdata.ruleengine.repository.RuleRepository;
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

    /** 根据 ID 获取规则 */
    public Rule getById(Long id) {
        if (id == null) {
            return null;
        }
        Optional<Rule> rule = ruleRepository.findById(id);
        return rule.orElse(null);
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
}
