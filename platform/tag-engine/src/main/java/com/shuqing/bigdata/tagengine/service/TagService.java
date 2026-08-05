package com.shuqing.bigdata.tagengine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shuqing.bigdata.tagengine.entity.TagDefinitionEntity;
import com.shuqing.bigdata.tagengine.entity.TagRuleEntity;
import com.shuqing.bigdata.tagengine.model.TagDefinition;
import com.shuqing.bigdata.tagengine.model.TagDefinitionRequest;
import com.shuqing.bigdata.tagengine.model.TagRule;
import com.shuqing.bigdata.tagengine.model.TagRuleRequest;
import com.shuqing.bigdata.tagengine.model.TagType;
import com.shuqing.bigdata.tagengine.repository.TagDefinitionRepository;
import com.shuqing.bigdata.tagengine.repository.TagRuleRepository;
import com.shuqing.bigdata.tagengine.store.TagStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 标签定义与规则管理服务。
 *
 * <p>编排 {@link TagStore}（宽表 DDL）与 JPA 仓储（元数据持久化）：</p>
 * <ul>
 *   <li>创建标签：先写元数据到 JPA，再通过 TagStore 在宽表加列</li>
 *   <li>删除标签：先删宽表列，再删元数据</li>
 *   <li>规则管理：纯元数据，仅走 JPA</li>
 * </ul>
 *
 * <p>对应详细设计 §3 标签模型、§6 接口契约。</p>
 */
@Service
public class TagService {

    private static final Logger log = LoggerFactory.getLogger(TagService.class);

    private final TagStore tagStore;
    private final TagDefinitionRepository tagDefRepo;
    private final TagRuleRepository tagRuleRepo;
    private final ObjectMapper objectMapper;

    public TagService(TagStore tagStore,
                      TagDefinitionRepository tagDefRepo,
                      TagRuleRepository tagRuleRepo,
                      ObjectMapper objectMapper) {
        this.tagStore = tagStore;
        this.tagDefRepo = tagDefRepo;
        this.tagRuleRepo = tagRuleRepo;
        this.objectMapper = objectMapper;
    }

    // ==================== 标签定义 ====================

    /**
     * 创建标签定义。
     *
     * @param req 创建请求
     * @return 已落地的标签定义
     */
    public TagDefinition createTagDefinition(TagDefinitionRequest req) {
        // 1. 通过 TagStore 创建（Mock 模式仅内存；Doris 模式会 ALTER ADD COLUMN）
        TagDefinition def = tagStore.createTagDefinition(req);

        // 2. 元数据持久化到 JPA（Mock 模式下也写 H2，便于重启恢复）
        TagDefinitionEntity entity = toEntity(def);
        tagDefRepo.save(entity);
        log.info("TagService.createTagDefinition: tagId={}, tenant={}", def.getTagId(), def.getTenantId());
        return def;
    }

    /**
     * 按 ID 获取标签定义。
     *
     * @param tagId 标签 ID
     * @return Optional 包装的标签定义
     */
    public Optional<TagDefinition> getTagDefinition(String tagId) {
        return tagDefRepo.findById(tagId).map(this::toModel);
    }

    /**
     * 列出指定租户的全部标签定义。
     *
     * @param tenantId 租户 ID
     * @return 标签定义列表
     */
    public List<TagDefinition> listTagDefinitions(String tenantId) {
        return tagDefRepo.findByTenantId(tenantId).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }

    /**
     * 删除标签定义。
     *
     * @param tagId 标签 ID
     * @return true 表示存在并已删除
     */
    public boolean deleteTagDefinition(String tagId) {
        Optional<TagDefinitionEntity> opt = tagDefRepo.findById(tagId);
        if (opt.isEmpty()) {
            return false;
        }
        TagDefinitionEntity entity = opt.get();
        // 1. 删宽表列（Mock 模式级联删画像字段；Doris 模式 ALTER DROP COLUMN）
        tagStore.deleteTagDefinition(tagId);
        // 2. 删规则元数据
        tagRuleRepo.findByTagId(tagId).forEach(r -> tagRuleRepo.deleteById(r.getRuleId()));
        // 3. 删标签元数据
        tagDefRepo.deleteById(tagId);
        log.info("TagService.deleteTagDefinition: tagId={}, column={}", tagId, entity.getColumnName());
        return true;
    }

    // ==================== 标签规则 ====================

    /**
     * 为标签添加规则。
     *
     * @param tagId 标签 ID
     * @param req   规则创建请求
     * @return 已落地的规则
     */
    public TagRule createTagRule(String tagId, TagRuleRequest req) {
        TagDefinitionEntity def = tagDefRepo.findById(tagId)
                .orElseThrow(() -> new IllegalArgumentException("tag not found: " + tagId));

        // 1. 通过 TagStore 创建（Mock 模式内存；Doris 模式抛 UnsupportedOperationException 由本服务接管）
        TagRule rule;
        try {
            rule = tagStore.createTagRule(tagId, req);
        } catch (UnsupportedOperationException e) {
            // Doris 模式：规则纯元数据，由本服务直接构造
            String ruleId = "rule-" + UUID.randomUUID();
            LocalDateTime now = LocalDateTime.now();
            rule = TagRule.builder()
                    .ruleId(ruleId)
                    .tagId(tagId)
                    .tenantId(def.getTenantId())
                    .condition(req.getCondition())
                    .value(req.getValue())
                    .priority(req.getPriority() != null ? req.getPriority() : 0)
                    .properties(req.getProperties())
                    .status("ACTIVE")
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
        }

        // 2. 持久化到 JPA
        TagRuleEntity entity = toEntity(rule);
        tagRuleRepo.save(entity);
        log.info("TagService.createTagRule: ruleId={}, tagId={}", rule.getRuleId(), tagId);
        return rule;
    }

    /**
     * 列出标签的全部规则（按 priority 降序）。
     *
     * @param tagId 标签 ID
     * @return 规则列表
     */
    public List<TagRule> getTagRules(String tagId) {
        return tagRuleRepo.findByTagId(tagId).stream()
                .map(this::toModel)
                .sorted((a, b) -> Integer.compare(
                        b.getPriority() == null ? 0 : b.getPriority(),
                        a.getPriority() == null ? 0 : a.getPriority()))
                .collect(Collectors.toList());
    }

    /**
     * 删除规则。
     *
     * @param ruleId 规则 ID
     * @return true 表示存在并已删除
     */
    public boolean deleteTagRule(String ruleId) {
        if (!tagRuleRepo.existsById(ruleId)) {
            return false;
        }
        tagRuleRepo.deleteById(ruleId);
        // 同步 Mock 存储（Doris 模式无操作）
        try {
            tagStore.deleteTagRule(ruleId);
        } catch (UnsupportedOperationException ignored) {
            // Doris 模式：规则纯元数据
        }
        return true;
    }

    // ==================== Entity <-> Model 转换 ====================

    private TagDefinitionEntity toEntity(TagDefinition def) {
        return TagDefinitionEntity.builder()
                .tagId(def.getTagId())
                .tenantId(def.getTenantId())
                .name(def.getName())
                .displayName(def.getDisplayName())
                .type(def.getType())
                .valueDomainJson(toJson(def.getValueDomain()))
                .description(def.getDescription())
                .columnName(def.getColumnName())
                .status(def.getStatus())
                .createdAt(def.getCreatedAt())
                .updatedAt(def.getUpdatedAt())
                .build();
    }

    private TagDefinition toModel(TagDefinitionEntity e) {
        return TagDefinition.builder()
                .tagId(e.getTagId())
                .tenantId(e.getTenantId())
                .name(e.getName())
                .displayName(e.getDisplayName())
                .type(e.getType())
                .valueDomain(fromJsonList(e.getValueDomainJson()))
                .description(e.getDescription())
                .columnName(e.getColumnName())
                .status(e.getStatus())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private TagRuleEntity toEntity(TagRule rule) {
        return TagRuleEntity.builder()
                .ruleId(rule.getRuleId())
                .tagId(rule.getTagId())
                .tenantId(rule.getTenantId())
                .condition(rule.getCondition())
                .value(rule.getValue())
                .priority(rule.getPriority())
                .propertiesJson(toJson(rule.getProperties()))
                .status(rule.getStatus())
                .createdAt(rule.getCreatedAt())
                .updatedAt(rule.getUpdatedAt())
                .build();
    }

    private TagRule toModel(TagRuleEntity e) {
        return TagRule.builder()
                .ruleId(e.getRuleId())
                .tagId(e.getTagId())
                .tenantId(e.getTenantId())
                .condition(e.getCondition())
                .value(e.getValue())
                .priority(e.getPriority())
                .properties(fromJsonMap(e.getPropertiesJson()))
                .status(e.getStatus())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("toJson failed", e);
            return null;
        }
    }

    private List<String> fromJsonList(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("fromJsonList failed: {}", json, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("fromJsonMap failed: {}", json, e);
            return null;
        }
    }
}