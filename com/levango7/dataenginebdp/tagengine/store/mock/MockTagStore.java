package com.shuqing.bigdata.tagengine.store.mock;

import com.shuqing.bigdata.tagengine.model.AudienceRequest;
import com.shuqing.bigdata.tagengine.model.AudienceResult;
import com.shuqing.bigdata.tagengine.model.BatchComputeResult;
import com.shuqing.bigdata.tagengine.model.ComputeRequest;
import com.shuqing.bigdata.tagengine.model.TagComputeResult;
import com.shuqing.bigdata.tagengine.model.TagDefinition;
import com.shuqing.bigdata.tagengine.model.TagDefinitionRequest;
import com.shuqing.bigdata.tagengine.model.TagQuery;
import com.shuqing.bigdata.tagengine.model.TagRule;
import com.shuqing.bigdata.tagengine.model.TagRuleRequest;
import com.shuqing.bigdata.tagengine.model.TagType;
import com.shuqing.bigdata.tagengine.model.UserProfile;
import com.shuqing.bigdata.tagengine.store.TagStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * {@link TagStore} 的内存 Mock 实现。
 *
 * <p>使用 {@link ConcurrentHashMap} 保证线程安全，所有数据仅存内存，
 * 进程重启即丢失。适用于：</p>
 * <ul>
 *   <li>单元测试与集成测试（无需外部依赖）</li>
 *   <li>开发环境无 Doris 集群时的 Mock 模式</li>
 *   <li>演示与功能验证</li>
 * </ul>
 *
 * <p>通过 {@code app.tag-store.type=mock}（默认）激活。</p>
 *
 * <p>标签计算语义：Mock 实现模拟 Spark ETL 的产出——
 * 对规则标签，按规则 priority 降序匹配用户事实数据，
 * 命中即赋值；对事实标签，直接拷贝用户事实字段。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.tag-store", name = "type", havingValue = "mock", matchIfMissing = true)
public class MockTagStore implements TagStore {

    private static final Logger log = LoggerFactory.getLogger(MockTagStore.class);

    /** 标签定义：tagId -> TagDefinition */
    private final Map<String, TagDefinition> tagDefinitions = new ConcurrentHashMap<>();

    /** 标签规则：ruleId -> TagRule */
    private final Map<String, TagRule> tagRules = new ConcurrentHashMap<>();

    /**
     * 用户事实数据：userId -> (columnName -> value)。
     * <p>Mock 模式下作为标签计算的源数据，模拟湖仓 Iceberg 事实表。</p>
     */
    private final Map<String, Map<String, Object>> userFacts = new ConcurrentHashMap<>();

    /**
     * 用户画像：userId -> UserProfile。
     * <p>标签计算结果写入此 Map，模拟 Doris 标签宽表。</p>
     */
    private final Map<String, UserProfile> profiles = new ConcurrentHashMap<>();

    /** 标签版本号生成器 */
    private final AtomicLong versionSeq = new AtomicLong(0);

    /**
     * 注入用户事实数据（测试辅助方法）。
     *
     * @param userId 用户 ID
     * @param facts  事实字段 Map
     */
    public void putUserFacts(String userId, Map<String, Object> facts) {
        userFacts.put(userId, new ConcurrentHashMap<>(facts));
    }

    /**
     * 批量注入用户事实数据。
     *
     * @param factsByUser userId -> facts
     */
    public void putAllUserFacts(Map<String, Map<String, Object>> factsByUser) {
        factsByUser.forEach((uid, f) -> putUserFacts(uid, f));
    }

    /**
     * 清空全部内存数据（测试辅助方法）。
     */
    public void clear() {
        tagDefinitions.clear();
        tagRules.clear();
        userFacts.clear();
        profiles.clear();
        versionSeq.set(0);
    }

    // ==================== 标签定义管理 ====================

    @Override
    public TagDefinition createTagDefinition(TagDefinitionRequest req) {
        String tagId = "tag-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        String columnName = req.getColumnName() != null ? req.getColumnName() : deriveColumnName(req.getName());
        TagDefinition def = TagDefinition.builder()
                .tagId(tagId)
                .tenantId(req.getTenantId())
                .name(req.getName())
                .displayName(req.getDisplayName() != null ? req.getDisplayName() : req.getName())
                .type(req.getType() != null ? req.getType() : TagType.RULE)
                .valueDomain(req.getValueDomain())
                .description(req.getDescription())
                .columnName(columnName)
                .status("ACTIVE")
                .createdAt(now)
                .updatedAt(now)
                .build();
        tagDefinitions.put(tagId, def);
        log.info("MockTagStore.createTagDefinition: tagId={}, name={}, tenant={}", tagId, req.getName(), req.getTenantId());
        return def;
    }

    @Override
    public TagDefinition getTagDefinition(String tagId) {
        return tagDefinitions.get(tagId);
    }

    @Override
    public List<TagDefinition> listTagDefinitions(String tenantId) {
        return tagDefinitions.values().stream()
                .filter(d -> Objects.equals(d.getTenantId(), tenantId))
                .sorted(Comparator.comparing(TagDefinition::getCreatedAt))
                .collect(Collectors.toList());
    }

    @Override
    public boolean deleteTagDefinition(String tagId) {
        TagDefinition removed = tagDefinitions.remove(tagId);
        if (removed == null) {
            return false;
        }
        // 级联删除规则
        tagRules.entrySet().removeIf(e -> Objects.equals(e.getValue().getTagId(), tagId));
        // 从画像中移除对应列
        String column = removed.getColumnName();
        if (column != null) {
            profiles.values().forEach(p -> {
                if (p.getTags() != null) {
                    p.getTags().remove(column);
                }
            });
        }
        log.info("MockTagStore.deleteTagDefinition: tagId={}", tagId);
        return true;
    }

    // ==================== 标签规则管理 ====================

    @Override
    public TagRule createTagRule(String tagId, TagRuleRequest req) {
        TagDefinition def = tagDefinitions.get(tagId);
        if (def == null) {
            throw new IllegalArgumentException("tag not found: " + tagId);
        }
        String ruleId = "rule-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        TagRule rule = TagRule.builder()
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
        tagRules.put(ruleId, rule);
        log.info("MockTagStore.createTagRule: ruleId={}, tagId={}, priority={}", ruleId, tagId, rule.getPriority());
        return rule;
    }

    @Override
    public List<TagRule> getTagRules(String tagId) {
        return tagRules.values().stream()
                .filter(r -> Objects.equals(r.getTagId(), tagId))
                .sorted(Comparator.comparing(TagRule::getPriority, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    @Override
    public boolean deleteTagRule(String ruleId) {
        return tagRules.remove(ruleId) != null;
    }

    // ==================== 标签计算 ====================

    @Override
    public TagComputeResult computeTag(String tagId, ComputeRequest req) {
        long start = System.currentTimeMillis();
        TagDefinition def = tagDefinitions.get(tagId);
        if (def == null) {
            return TagComputeResult.builder()
                    .tagId(tagId)
                    .status("FAILED")
                    .errorMessage("tag not found: " + tagId)
                    .costMs(System.currentTimeMillis() - start)
                    .build();
        }
        String column = def.getColumnName();
        String tenantId = def.getTenantId();
        String version = "v" + versionSeq.incrementAndGet();
        long affected = 0;

        if (def.getType() == TagType.FACT) {
            // 事实标签：直接从用户事实字段拷贝到画像
            for (Map.Entry<String, Map<String, Object>> entry : userFacts.entrySet()) {
                String userId = entry.getKey();
                Map<String, Object> facts = entry.getValue();
                // 按 tenant_id 字段过滤事实数据，缺省视为同租户
                Object factTenant = facts.get("tenant_id");
                if (factTenant != null && !Objects.equals(factTenant, tenantId)) {
                    continue;
                }
                if (facts.containsKey(column)) {
                    writeTagValue(userId, tenantId, column, facts.get(column), version);
                    affected++;
                }
            }
        } else if (def.getType() == TagType.RULE) {
            // 规则标签：按规则 priority 降序匹配
            List<TagRule> rules = getTagRules(tagId);
            for (Map.Entry<String, Map<String, Object>> entry : userFacts.entrySet()) {
                String userId = entry.getKey();
                Map<String, Object> facts = entry.getValue();
                // 按 tenant_id 字段过滤
                Object factTenant = facts.get("tenant_id");
                if (factTenant != null && !Objects.equals(factTenant, tenantId)) {
                    continue;
                }
                for (TagRule rule : rules) {
                    if (!"ACTIVE".equals(rule.getStatus())) {
                        continue;
                    }
                    if (matchCondition(rule.getCondition(), facts)) {
                        writeTagValue(userId, tenantId, column, rule.getValue(), version);
                        affected++;
                        break;
                    }
                }
            }
        } else {
            // 挖掘标签：Mock 模式无模型，跳过；可由测试通过 putUserFacts 预置 column 字段
            for (Map.Entry<String, Map<String, Object>> entry : userFacts.entrySet()) {
                Map<String, Object> facts = entry.getValue();
                Object factTenant = facts.get("tenant_id");
                if (factTenant != null && !Objects.equals(factTenant, tenantId)) {
                    continue;
                }
                if (facts.containsKey(column)) {
                    writeTagValue(entry.getKey(), tenantId, column, facts.get(column), version);
                    affected++;
                }
            }
        }

        long cost = System.currentTimeMillis() - start;
        log.info("MockTagStore.computeTag: tagId={}, affected={}, version={}, costMs={}", tagId, affected, version, cost);
        return TagComputeResult.builder()
                .tagId(tagId)
                .status("SUCCESS")
                .affectedRows(affected)
                .tagVersion(version)
                .costMs(cost)
                .build();
    }

    @Override
    public BatchComputeResult batchCompute(List<String> tagIds, ComputeRequest req) {
        long start = System.currentTimeMillis();
        List<TagComputeResult> results = new ArrayList<>();
        long success = 0;
        long failed = 0;
        for (String tagId : tagIds) {
            TagComputeResult r = computeTag(tagId, req);
            results.add(r);
            if ("SUCCESS".equals(r.getStatus())) {
                success++;
            } else {
                failed++;
            }
        }
        long totalCost = System.currentTimeMillis() - start;
        return BatchComputeResult.builder()
                .results(results)
                .successCount(success)
                .failedCount(failed)
                .totalCostMs(totalCost)
                .build();
    }

    // ==================== 画像查询 ====================

    @Override
    public UserProfile getProfile(String userId) {
        return profiles.get(userId);
    }

    @Override
    public List<UserProfile> queryByTags(TagQuery query) {
        return profiles.values().stream()
                .filter(p -> matchQuery(query, p))
                .collect(Collectors.toList());
    }

    @Override
    public long countByTags(TagQuery query) {
        return profiles.values().stream()
                .filter(p -> matchQuery(query, p))
                .count();
    }

    // ==================== 人群圈选 ====================

    @Override
    public AudienceResult selectAudience(AudienceRequest req) {
        long start = System.currentTimeMillis();
        List<UserProfile> candidates = new ArrayList<>();
        for (UserProfile p : profiles.values()) {
            if (req.getTenantId() != null && !Objects.equals(req.getTenantId(), p.getTenantId())) {
                continue;
            }
            if (req.getInclude() != null && !matchQuery(req.getInclude(), p)) {
                continue;
            }
            if (req.getExclude() != null && matchQuery(req.getExclude(), p)) {
                continue;
            }
            candidates.add(p);
        }
        long count = candidates.size();
        List<String> ids = null;
        boolean truncated = false;
        if (req.isReturnIds()) {
            int limit = req.getLimit() != null ? req.getLimit() : Integer.MAX_VALUE;
            if (candidates.size() > limit) {
                truncated = true;
                ids = candidates.stream().limit(limit).map(UserProfile::getUserId).collect(Collectors.toList());
            } else {
                ids = candidates.stream().map(UserProfile::getUserId).collect(Collectors.toList());
            }
        }
        long cost = System.currentTimeMillis() - start;
        return AudienceResult.builder()
                .count(count)
                .userIds(ids)
                .truncated(truncated)
                .costMs(cost)
                .build();
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 将标签值写入用户画像。
     */
    private void writeTagValue(String userId, String tenantId, String column, Object value, String version) {
        UserProfile p = profiles.computeIfAbsent(userId, k -> UserProfile.builder()
                .userId(k)
                .tenantId(tenantId)
                .tags(new HashMap<>())
                .tagVersion(version)
                .updateTs(LocalDateTime.now())
                .build());
        if (p.getTags() == null) {
            p.setTags(new HashMap<>());
        }
        p.getTags().put(column, value);
        p.setTagVersion(version);
        p.setUpdateTs(LocalDateTime.now());
        if (p.getTenantId() == null) {
            p.setTenantId(tenantId);
        }
    }

    /**
     * 简单条件匹配：支持 {@code key op value} 形式与 AND/OR 组合。
     * <p>为避免 SQL 注入风险，仅支持白名单运算符与字面量比较，
     * 不执行任意表达式。复杂规则应由 DorisSqlGenerator 翻译为参数化 SQL。</p>
     *
     * <p>支持的语法（示例）：</p>
     * <ul>
     *   <li>{@code "total_amount >= 5000"}</li>
     *   <li>{@code "last_order_ts >= 30d"}  — 近 30 天（Mock 简化为 true）</li>
     *   <li>{@code "user_level = 活跃"}</li>
     *   <li>{@code "total_amount >= 5000 AND user_level = 活跃"}</li>
     * </ul>
     */
    boolean matchCondition(String condition, Map<String, Object> facts) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        // 拆分 AND / OR（仅支持两层，足够 Mock）
        if (condition.contains(" AND ")) {
            String[] parts = condition.split(" AND ");
            for (String p : parts) {
                if (!matchCondition(p.trim(), facts)) {
                    return false;
                }
            }
            return true;
        }
        if (condition.contains(" OR ")) {
            String[] parts = condition.split(" OR ");
            for (String p : parts) {
                if (matchCondition(p.trim(), facts)) {
                    return true;
                }
            }
            return false;
        }
        // 单条件：column op value
        return matchSingleCondition(condition.trim(), facts);
    }

    /**
     * 匹配单条件。
     * <p>按运算符长度降序匹配，避免 {@code >=} 被 {@code >} 提前命中。</p>
     */
    private boolean matchSingleCondition(String expr, Map<String, Object> facts) {
        // 支持 >=, <=, !=, =, >, <（按长度降序）
        for (String op : new String[]{">=", "<=", "!=", "=", ">", "<"}) {
            int idx = expr.indexOf(op);
            if (idx > 0) {
                String col = expr.substring(0, idx).trim();
                String val = expr.substring(idx + op.length()).trim();
                return compareValue(facts.get(col), op, val);
            }
        }
        // 不识别的条件默认 true（保守命中），便于 Mock 测试
        return true;
    }

    /**
     * 值比较。
     */
    @SuppressWarnings("unchecked")
    private boolean compareValue(Object actual, String op, String expected) {
        if (actual == null) {
            return false;
        }
        // 数值比较
        Double aNum = toDouble(actual);
        Double eNum = toDouble(expected);
        if (aNum != null && eNum != null) {
            return switch (op) {
                case ">=" -> aNum >= eNum;
                case "<=" -> aNum <= eNum;
                case ">" -> aNum > eNum;
                case "<" -> aNum < eNum;
                case "=" -> aNum.equals(eNum);
                case "!=" -> !aNum.equals(eNum);
                default -> false;
            };
        }
        // 字符串比较
        String aStr = String.valueOf(actual);
        return switch (op) {
            case "=" -> aStr.equals(expected);
            case "!=" -> !aStr.equals(expected);
            case ">=" -> aStr.compareTo(expected) >= 0;
            case "<=" -> aStr.compareTo(expected) <= 0;
            case ">" -> aStr.compareTo(expected) > 0;
            case "<" -> aStr.compareTo(expected) < 0;
            default -> false;
        };
    }

    /**
     * 尝试转为 Double；失败返回 null。
     */
    private Double toDouble(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 匹配 TagQuery（递归）。
     */
    boolean matchQuery(TagQuery query, UserProfile profile) {
        if (query == null) {
            return true;
        }
        if (query.getTenantId() != null && !Objects.equals(query.getTenantId(), profile.getTenantId())) {
            return false;
        }
        Map<String, Object> tags = profile.getTags() != null ? profile.getTags() : Map.of();
        String logic = query.getLogic() != null ? query.getLogic().toUpperCase() : "AND";
        List<TagQuery.Condition> conds = query.getConditions() != null ? query.getConditions() : List.of();
        List<TagQuery> nested = query.getNested() != null ? query.getNested() : List.of();

        if ("OR".equals(logic)) {
            for (TagQuery.Condition c : conds) {
                if (matchCondition(c, tags)) {
                    return true;
                }
            }
            for (TagQuery q : nested) {
                if (matchQuery(q, profile)) {
                    return true;
                }
            }
            return false;
        }
        // 默认 AND
        for (TagQuery.Condition c : conds) {
            if (!matchCondition(c, tags)) {
                return false;
            }
        }
        for (TagQuery q : nested) {
            if (!matchQuery(q, profile)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 匹配单个 Condition。
     */
    private boolean matchCondition(TagQuery.Condition c, Map<String, Object> tags) {
        Object actual = tags.get(c.getColumnName());
        Object expected = c.getValue();
        String op = c.getOp() != null ? c.getOp() : "=";
        if (actual == null) {
            return "IS NULL".equalsIgnoreCase(op);
        }
        if ("IS NOT NULL".equalsIgnoreCase(op)) {
            return true;
        }
        if ("IN".equalsIgnoreCase(op) && expected instanceof List<?> list) {
            return list.stream().anyMatch(e -> Objects.equals(String.valueOf(e), String.valueOf(actual)));
        }
        if ("NOT IN".equalsIgnoreCase(op) && expected instanceof List<?> list) {
            return list.stream().noneMatch(e -> Objects.equals(String.valueOf(e), String.valueOf(actual)));
        }
        return compareValue(actual, op, String.valueOf(expected));
    }

    /**
     * 由标签名推导 Doris 列名：小写 + 下划线。
     */
    private String deriveColumnName(String name) {
        if (name == null) {
            return "tag_col";
        }
        return name.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_");
    }
}