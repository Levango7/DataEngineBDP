package com.levango7.dataenginebdp.tagengine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.tagengine.entity.TagDefinitionEntity;
import com.levango7.dataenginebdp.tagengine.entity.TagRuleEntity;
import com.levango7.dataenginebdp.tagengine.model.TagDefinition;
import com.levango7.dataenginebdp.tagengine.model.TagDefinitionRequest;
import com.levango7.dataenginebdp.tagengine.model.TagRule;
import com.levango7.dataenginebdp.tagengine.model.TagRuleRequest;
import com.levango7.dataenginebdp.tagengine.model.TagType;
import com.levango7.dataenginebdp.tagengine.repository.TagDefinitionRepository;
import com.levango7.dataenginebdp.tagengine.repository.TagRuleRepository;
import com.levango7.dataenginebdp.tagengine.store.TagStore;
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
     * 创建标签定义（租户隔离：tenantId 强制取当前租户）。
     *
     * <p>R8 租户隔离补齐：请求体中的 {@code tenantId} 一律被当前租户覆盖，
     * 防止调用方（含非 HTTP 调用方）通过请求体把标签建到别的租户下。</p>
     *
     * @param req 创建请求
     * @return 已落地的标签定义
     * @throws IllegalStateException 缺少租户上下文（fail-closed）
     */
    public TagDefinition createTagDefinition(TagDefinitionRequest req) {
        // 强制覆盖为当前租户，不信任请求体中的 tenantId
        req.setTenantId(requireTenant());
        // 1. 通过 TagStore 创建（Mock 模式仅内存；Doris 模式会 ALTER ADD COLUMN）
        TagDefinition def = tagStore.createTagDefinition(req);

        // 2. 元数据持久化到 JPA（Mock 模式下也写 H2，便于重启恢复）
        TagDefinitionEntity entity = toEntity(def);
        tagDefRepo.save(entity);
        log.info("TagService.createTagDefinition: tagId={}, tenant={}", def.getTagId(), def.getTenantId());
        return def;
    }

    /**
     * 按 ID 获取标签定义（租户隔离：仅返回属于当前租户的标签）。
     *
     * @param tagId 标签 ID
     * @return Optional 包装的标签定义；不属于当前租户时返回 empty
     * @throws IllegalStateException 缺少租户上下文（fail-closed）
     */
    public Optional<TagDefinition> getTagDefinition(String tagId) {
        return getTagDefinition(tagId, requireTenant());
    }

    /**
     * 按 ID 与租户 ID 联合获取标签定义（租户隔离）。
     *
     * @param tagId    标签 ID
     * @param tenantId 租户 ID
     * @return Optional 包装的标签定义；不属于该租户时返回 empty
     */
    public Optional<TagDefinition> getTagDefinition(String tagId, String tenantId) {
        if (tagId == null || tenantId == null) {
            return Optional.empty();
        }
        return tagDefRepo.findById(tagId)
                .filter(e -> tenantId.equals(e.getTenantId()))
                .map(this::toModel);
    }

    /**
     * 列出当前租户的全部标签定义。
     *
     * <p>R8 租户隔离补齐：租户一律取自 {@link TenantContext}，
     * <b>入参 {@code tenantId} 已被忽略</b>（历史遗留签名，保留只为兼容既有调用方），
     * 任何调用方都无法通过它越权列出其他租户的标签。</p>
     *
     * @param tenantId 租户 ID（已忽略，保留仅为兼容旧签名）
     * @return 标签定义列表
     * @throws IllegalStateException 缺少租户上下文（fail-closed）
     */
    public List<TagDefinition> listTagDefinitions(String tenantId) {
        return listDefinitionsOfTenant(requireTenant());
    }

    /**
     * 列出指定租户的全部标签定义（内部实现，供租户感知路径复用）。
     *
     * @param tenantId 租户 ID
     * @return 标签定义列表
     */
    private List<TagDefinition> listDefinitionsOfTenant(String tenantId) {
        return tagDefRepo.findByTenantId(tenantId).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }

    /**
     * 删除标签定义（租户隔离：只能删当前租户自己的标签）。
     *
     * @param tagId 标签 ID
     * @return true 表示存在、属于当前租户且已删除
     * @throws IllegalStateException 缺少租户上下文（fail-closed）
     */
    public boolean deleteTagDefinition(String tagId) {
        return deleteTagDefinition(tagId, requireTenant());
    }

    /**
     * 删除标签定义（租户隔离）。
     *
     * <p>先按 (tagId, tenantId) 校验存在性与租户归属，不匹配返回 {@code false}；
     * 命中则删除标签及其全部规则与宽表列。</p>
     *
     * @param tagId    标签 ID
     * @param tenantId 租户 ID
     * @return 删除成功返回 {@code true}；不存在或不属于该租户返回 {@code false}
     */
    public boolean deleteTagDefinition(String tagId, String tenantId) {
        if (tagId == null || tenantId == null) {
            return false;
        }
        Optional<TagDefinitionEntity> opt = tagDefRepo.findById(tagId)
                .filter(e -> tenantId.equals(e.getTenantId()));
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
        log.info("TagService.deleteTagDefinition: tagId={}, column={}, tenant={}", tagId, entity.getColumnName(), tenantId);
        return true;
    }

    // ==================== 标签规则 ====================

    /**
     * 为标签添加规则（租户隔离：只能给当前租户自己的标签加规则）。
     *
     * @param tagId 标签 ID
     * @param req   规则创建请求
     * @return 已落地的规则
     * @throws IllegalArgumentException 标签不存在或不属于当前租户
     * @throws IllegalStateException    缺少租户上下文（fail-closed）
     */
    public TagRule createTagRule(String tagId, TagRuleRequest req) {
        return createTagRule(tagId, req, requireTenant());
    }

    /**
     * 为标签添加规则（租户隔离）。
     *
     * <p>先按 (tagId, tenantId) 校验标签存在性与租户归属，不匹配抛
     * {@link IllegalArgumentException}；命中则创建规则并锁定 tenantId。</p>
     *
     * @param tagId    标签 ID
     * @param req      规则创建请求
     * @param tenantId 租户 ID
     * @return 已落地的规则
     * @throws IllegalArgumentException 标签不存在或不属于该租户
     */
    public TagRule createTagRule(String tagId, TagRuleRequest req, String tenantId) {
        if (tagId == null || tenantId == null) {
            throw new IllegalArgumentException("tagId 与 tenantId 不能为空");
        }
        TagDefinitionEntity def = tagDefRepo.findById(tagId)
                .filter(e -> tenantId.equals(e.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("tag not found or tenant mismatch: " + tagId));

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
        log.info("TagService.createTagRule: ruleId={}, tagId={}, tenant={}", rule.getRuleId(), tagId, tenantId);
        return rule;
    }

    /**
     * 列出标签的全部规则（租户隔离：只能列当前租户自己标签的规则）。
     *
     * @param tagId 标签 ID
     * @return 规则列表；标签不属于当前租户时返回空列表
     * @throws IllegalStateException 缺少租户上下文（fail-closed）
     */
    public List<TagRule> getTagRules(String tagId) {
        return getTagRules(tagId, requireTenant());
    }

    /**
     * 列出标签的全部规则（租户隔离，按 priority 降序）。
     *
     * <p>先按 (tagId, tenantId) 校验标签存在性与租户归属，不匹配返回空列表；
     * 命中则返回该标签的全部规则。</p>
     *
     * @param tagId    标签 ID
     * @param tenantId 租户 ID
     * @return 规则列表；标签不存在或不属于该租户时返回空列表
     */
    public List<TagRule> getTagRules(String tagId, String tenantId) {
        if (tagId == null || tenantId == null) {
            return List.of();
        }
        boolean owned = tagDefRepo.findById(tagId)
                .filter(e -> tenantId.equals(e.getTenantId()))
                .isPresent();
        if (!owned) {
            return List.of();
        }
        return tagRuleRepo.findByTagId(tagId).stream()
                .map(this::toModel)
                .sorted((a, b) -> Integer.compare(
                        b.getPriority() == null ? 0 : b.getPriority(),
                        a.getPriority() == null ? 0 : a.getPriority()))
                .collect(Collectors.toList());
    }


    /**
     * 删除规则（租户隔离：只能删属于当前租户的规则）。
     *
     * <p>规则实体本身记录了 {@code tenantId}，此处按「规则的 tenantId 必须等于当前租户」
     * 校验，防止跨租户删规则。</p>
     *
     * @param ruleId 规则 ID
     * @return true 表示存在、属于当前租户且已删除；不存在或不属于当前租户返回 false
     * @throws IllegalStateException 缺少租户上下文（fail-closed）
     */
    public boolean deleteTagRule(String ruleId) {
        String tenantId = requireTenant();
        TagRuleEntity rule = tagRuleRepo.findById(ruleId).orElse(null);
        if (rule == null || !tenantId.equals(rule.getTenantId())) {
            log.warn("TagService.deleteTagRule: 规则不存在或不属于当前租户: ruleId={}, tenant={}",
                    ruleId, tenantId);
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

    // ==================== 租户上下文 ====================

    /**
     * 取当前租户 ID；缺失时 fail-closed 抛异常。
     *
     * <p>R8 租户隔离补齐：Service 层不再信任任何入参/请求体里的 tenantId，
     * 一律以 {@link TenantContext}（由 {@code JwtAuthFilter} 从 JWT 写入）为准。</p>
     *
     * @return 当前租户 ID
     * @throws IllegalStateException 租户上下文缺失
     */
    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
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