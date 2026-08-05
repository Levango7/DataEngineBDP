package com.shuqing.bigdata.tagengine.store.mock;

import com.shuqing.bigdata.tagengine.model.AudienceRequest;
import com.shuqing.bigdata.tagengine.model.AudienceResult;
import com.shuqing.bigdata.tagengine.model.ComputeRequest;
import com.shuqing.bigdata.tagengine.model.TagComputeResult;
import com.shuqing.bigdata.tagengine.model.TagDefinition;
import com.shuqing.bigdata.tagengine.model.TagDefinitionRequest;
import com.shuqing.bigdata.tagengine.model.TagQuery;
import com.shuqing.bigdata.tagengine.model.TagRule;
import com.shuqing.bigdata.tagengine.model.TagRuleRequest;
import com.shuqing.bigdata.tagengine.model.TagType;
import com.shuqing.bigdata.tagengine.model.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MockTagStore} 单元测试。
 *
 * <p>覆盖标签定义 CRUD、规则管理、标签计算、画像查询、人群圈选全链路。</p>
 */
@DisplayName("MockTagStore 标签存储内存实现测试")
class MockTagStoreTest {

    private MockTagStore store;

    @BeforeEach
    void setUp() {
        store = new MockTagStore();
    }

    // ==================== 标签定义 ====================

    @Test
    @DisplayName("创建标签定义：返回 tagId 与时间戳")
    void createTagDefinition_shouldAssignIdAndTimestamps() {
        TagDefinitionRequest req = TagDefinitionRequest.builder()
                .tenantId("t1").name("user_level").type(TagType.RULE)
                .valueDomain(List.of("新客", "活跃", "沉睡", "流失"))
                .build();
        TagDefinition def = store.createTagDefinition(req);

        assertNotNull(def.getTagId());
        assertEquals("t1", def.getTenantId());
        assertEquals("user_level", def.getName());
        assertEquals(TagType.RULE, def.getType());
        assertEquals("ACTIVE", def.getStatus());
        assertNotNull(def.getCreatedAt());
        assertEquals("user_level", def.getColumnName());
    }

    @Test
    @DisplayName("列出标签定义：按租户过滤")
    void listTagDefinitions_shouldFilterByTenant() {
        store.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("tag_a").type(TagType.FACT).build());
        store.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("tag_b").type(TagType.RULE).build());
        store.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t2").name("tag_c").type(TagType.RULE).build());

        List<TagDefinition> t1Tags = store.listTagDefinitions("t1");
        assertEquals(2, t1Tags.size());
        assertEquals(1, store.listTagDefinitions("t2").size());
        assertEquals(0, store.listTagDefinitions("t3").size());
    }

    @Test
    @DisplayName("删除标签定义：级联删除规则与画像字段")
    void deleteTagDefinition_shouldCascade() {
        TagDefinition def = store.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("level").type(TagType.RULE).build());
        store.createTagRule(def.getTagId(), TagRuleRequest.builder()
                .condition("total_amount >= 5000").value("活跃").priority(10).build());
        // 模拟已计算画像
        store.putUserFacts("u1", Map.of("tenant_id", "t1", "total_amount", 8000));
        store.computeTag(def.getTagId(), ComputeRequest.builder().tenantId("t1").mode("full").build());
        assertNotNull(store.getProfile("u1"));
        assertTrue(store.getProfile("u1").getTags().containsKey("level"));

        boolean deleted = store.deleteTagDefinition(def.getTagId());
        assertTrue(deleted);
        assertNull(store.getTagDefinition(def.getTagId()));
        assertTrue(store.getTagRules(def.getTagId()).isEmpty());
        // 画像中对应列被移除
        UserProfile p = store.getProfile("u1");
        assertNotNull(p);
        assertFalse(p.getTags().containsKey("level"));
    }

    @Test
    @DisplayName("删除不存在的标签定义返回 false")
    void deleteTagDefinition_nonExisting_shouldReturnFalse() {
        assertFalse(store.deleteTagDefinition("non-existing"));
    }

    // ==================== 标签规则 ====================

    @Test
    @DisplayName("创建规则并按 priority 降序列出")
    void createAndListRules_shouldSortByPriorityDesc() {
        TagDefinition def = store.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("level").type(TagType.RULE).build());
        store.createTagRule(def.getTagId(), TagRuleRequest.builder()
                .condition("total_amount >= 5000").value("活跃").priority(10).build());
        store.createTagRule(def.getTagId(), TagRuleRequest.builder()
                .condition("total_amount >= 100").value("新客").priority(5).build());
        store.createTagRule(def.getTagId(), TagRuleRequest.builder()
                .condition("total_amount < 100").value("沉睡").priority(1).build());

        List<TagRule> rules = store.getTagRules(def.getTagId());
        assertEquals(3, rules.size());
        assertEquals(10, rules.get(0).getPriority());
        assertEquals(5, rules.get(1).getPriority());
        assertEquals(1, rules.get(2).getPriority());
    }

    @Test
    @DisplayName("创建规则：标签不存在时抛异常")
    void createRule_tagNotFound_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                store.createTagRule("non-existing", TagRuleRequest.builder()
                        .condition("x >= 1").value("v").build()));
    }

    // ==================== 标签计算 ====================

    @Test
    @DisplayName("规则标签计算：按 priority 降序匹配命中赋值")
    void computeTag_ruleTag_shouldMatchByPriority() {
        TagDefinition def = store.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("level").type(TagType.RULE)
                .valueDomain(List.of("新客", "活跃", "沉睡")).build());
        store.createTagRule(def.getTagId(), TagRuleRequest.builder()
                .condition("total_amount >= 5000").value("活跃").priority(10).build());
        store.createTagRule(def.getTagId(), TagRuleRequest.builder()
                .condition("total_amount >= 100").value("新客").priority(5).build());
        store.createTagRule(def.getTagId(), TagRuleRequest.builder()
                .condition("total_amount < 100").value("沉睡").priority(1).build());

        store.putUserFacts("u1", Map.of("tenant_id", "t1", "total_amount", 8000));
        store.putUserFacts("u2", Map.of("tenant_id", "t1", "total_amount", 200));
        store.putUserFacts("u3", Map.of("tenant_id", "t1", "total_amount", 50));

        TagComputeResult result = store.computeTag(def.getTagId(),
                ComputeRequest.builder().tenantId("t1").mode("full").build());

        assertEquals("SUCCESS", result.getStatus());
        assertEquals(3, result.getAffectedRows());
        assertEquals("活跃", store.getProfile("u1").getTags().get("level"));
        assertEquals("新客", store.getProfile("u2").getTags().get("level"));
        assertEquals("沉睡", store.getProfile("u3").getTags().get("level"));
    }

    @Test
    @DisplayName("事实标签计算：直接拷贝事实字段")
    void computeTag_factTag_shouldCopyFactField() {
        TagDefinition def = store.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("total_amount").type(TagType.FACT).build());
        store.putUserFacts("u1", Map.of("tenant_id", "t1", "total_amount", 1234.56));
        store.putUserFacts("u2", Map.of("tenant_id", "t1", "total_amount", 789.0));

        TagComputeResult result = store.computeTag(def.getTagId(),
                ComputeRequest.builder().tenantId("t1").mode("full").build());

        assertEquals("SUCCESS", result.getStatus());
        assertEquals(2, result.getAffectedRows());
        assertEquals(1234.56, store.getProfile("u1").getTags().get("total_amount"));
        assertEquals(789.0, store.getProfile("u2").getTags().get("total_amount"));
    }

    @Test
    @DisplayName("计算不存在的标签返回 FAILED")
    void computeTag_nonExisting_shouldFail() {
        TagComputeResult r = store.computeTag("non-existing",
                ComputeRequest.builder().tenantId("t1").mode("full").build());
        assertEquals("FAILED", r.getStatus());
        assertNotNull(r.getErrorMessage());
    }

    @Test
    @DisplayName("批量计算：聚合多标签结果")
    void batchCompute_shouldAggregateResults() {
        TagDefinition d1 = store.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("amount").type(TagType.FACT).build());
        TagDefinition d2 = store.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("days").type(TagType.FACT).build());
        store.putUserFacts("u1", Map.of("tenant_id", "t1", "amount", 100, "days", 30));

        var result = store.batchCompute(List.of(d1.getTagId(), d2.getTagId()),
                ComputeRequest.builder().tenantId("t1").mode("full").build());

        assertEquals(2, result.getResults().size());
        assertEquals(2, result.getSuccessCount());
        assertEquals(0, result.getFailedCount());
    }

    // ==================== 画像查询 ====================

    @Test
    @DisplayName("按标签条件查询用户列表：AND 逻辑")
    void queryByTags_andLogic_shouldMatchAllConditions() {
        // 预置画像
        store.putUserFacts("u1", Map.of("tenant_id", "t1"));
        store.putUserFacts("u2", Map.of("tenant_id", "t1"));
        store.putUserFacts("u3", Map.of("tenant_id", "t1"));
        // 直接写画像（绕过计算）
        store.putUserFacts("u1", Map.of("tenant_id", "t1", "level", "活跃", "amount", 8000));
        store.putUserFacts("u2", Map.of("tenant_id", "t1", "level", "活跃", "amount", 3000));
        store.putUserFacts("u3", Map.of("tenant_id", "t1", "level", "沉睡", "amount", 100));
        // 用事实标签计算把 facts 拷贝到画像
        TagDefinition levelDef = store.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("level").type(TagType.FACT).build());
        TagDefinition amtDef = store.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("amount").type(TagType.FACT).build());
        store.computeTag(levelDef.getTagId(), ComputeRequest.builder().tenantId("t1").mode("full").build());
        store.computeTag(amtDef.getTagId(), ComputeRequest.builder().tenantId("t1").mode("full").build());

        TagQuery query = TagQuery.builder()
                .tenantId("t1")
                .logic("AND")
                .conditions(List.of(
                        TagQuery.Condition.builder().columnName("level").op("=").value("活跃").build(),
                        TagQuery.Condition.builder().columnName("amount").op(">=").value(5000).build()
                ))
                .build();

        List<UserProfile> matched = store.queryByTags(query);
        assertEquals(1, matched.size());
        assertEquals("u1", matched.get(0).getUserId());
    }

    @Test
    @DisplayName("按标签条件统计人数：OR 逻辑")
    void countByTags_orLogic_shouldCountAnyMatch() {
        TagDefinition levelDef = store.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("level").type(TagType.FACT).build());
        store.putUserFacts("u1", Map.of("tenant_id", "t1", "level", "活跃"));
        store.putUserFacts("u2", Map.of("tenant_id", "t1", "level", "沉睡"));
        store.putUserFacts("u3", Map.of("tenant_id", "t1", "level", "流失"));
        store.computeTag(levelDef.getTagId(), ComputeRequest.builder().tenantId("t1").mode("full").build());

        TagQuery query = TagQuery.builder()
                .tenantId("t1")
                .logic("OR")
                .conditions(List.of(
                        TagQuery.Condition.builder().columnName("level").op("=").value("活跃").build(),
                        TagQuery.Condition.builder().columnName("level").op("=").value("沉睡").build()
                ))
                .build();

        assertEquals(2, store.countByTags(query));
    }

    @Test
    @DisplayName("getProfile：不存在返回 null")
    void getProfile_nonExisting_shouldReturnNull() {
        assertNull(store.getProfile("non-existing"));
    }

    // ==================== 人群圈选 ====================

    @Test
    @DisplayName("人群圈选：include 条件命中 + returnIds")
    void selectAudience_withIncludeAndReturnIds_shouldReturnIds() {
        TagDefinition levelDef = store.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("level").type(TagType.FACT).build());
        store.putUserFacts("u1", Map.of("tenant_id", "t1", "level", "活跃"));
        store.putUserFacts("u2", Map.of("tenant_id", "t1", "level", "活跃"));
        store.putUserFacts("u3", Map.of("tenant_id", "t1", "level", "沉睡"));
        store.computeTag(levelDef.getTagId(), ComputeRequest.builder().tenantId("t1").mode("full").build());

        AudienceRequest req = AudienceRequest.builder()
                .tenantId("t1")
                .include(TagQuery.builder()
                        .tenantId("t1")
                        .conditions(List.of(
                                TagQuery.Condition.builder().columnName("level").op("=").value("活跃").build()
                        ))
                        .build())
                .returnIds(true)
                .limit(10)
                .build();

        AudienceResult result = store.selectAudience(req);
        assertEquals(2, result.getCount());
        assertNotNull(result.getUserIds());
        assertEquals(2, result.getUserIds().size());
        assertFalse(result.isTruncated());
    }

    @Test
    @DisplayName("人群圈选：exclude 条件剔除")
    void selectAudience_withExclude_shouldExcludeMatched() {
        TagDefinition levelDef = store.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("level").type(TagType.FACT).build());
        TagDefinition riskDef = store.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("risk").type(TagType.FACT).build());
        store.putUserFacts("u1", Map.of("tenant_id", "t1", "level", "活跃", "risk", "高"));
        store.putUserFacts("u2", Map.of("tenant_id", "t1", "level", "活跃", "risk", "低"));
        store.putUserFacts("u3", Map.of("tenant_id", "t1", "level", "沉睡", "risk", "低"));
        store.computeTag(levelDef.getTagId(), ComputeRequest.builder().tenantId("t1").mode("full").build());
        store.computeTag(riskDef.getTagId(), ComputeRequest.builder().tenantId("t1").mode("full").build());

        AudienceRequest req = AudienceRequest.builder()
                .tenantId("t1")
                .include(TagQuery.builder()
                        .tenantId("t1")
                        .conditions(List.of(
                                TagQuery.Condition.builder().columnName("level").op("=").value("活跃").build()
                        ))
                        .build())
                .exclude(TagQuery.builder()
                        .tenantId("t1")
                        .conditions(List.of(
                                TagQuery.Condition.builder().columnName("risk").op("=").value("高").build()
                        ))
                        .build())
                .returnIds(true)
                .limit(10)
                .build();

        AudienceResult result = store.selectAudience(req);
        assertEquals(1, result.getCount());
        assertEquals("u2", result.getUserIds().get(0));
    }

    @Test
    @DisplayName("人群圈选：limit 截断标记 truncated=true")
    void selectAudience_limitTruncation_shouldMarkTruncated() {
        TagDefinition levelDef = store.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("level").type(TagType.FACT).build());
        store.putUserFacts("u1", Map.of("tenant_id", "t1", "level", "活跃"));
        store.putUserFacts("u2", Map.of("tenant_id", "t1", "level", "活跃"));
        store.putUserFacts("u3", Map.of("tenant_id", "t1", "level", "活跃"));
        store.computeTag(levelDef.getTagId(), ComputeRequest.builder().tenantId("t1").mode("full").build());

        AudienceRequest req = AudienceRequest.builder()
                .tenantId("t1")
                .include(TagQuery.builder()
                        .tenantId("t1")
                        .conditions(List.of(
                                TagQuery.Condition.builder().columnName("level").op("=").value("活跃").build()
                        ))
                        .build())
                .returnIds(true)
                .limit(2)
                .build();

        AudienceResult result = store.selectAudience(req);
        assertEquals(3, result.getCount());
        assertEquals(2, result.getUserIds().size());
        assertTrue(result.isTruncated());
    }

    @Test
    @DisplayName("人群圈选：returnIds=false 仅返回 count")
    void selectAudience_noReturnIds_shouldOnlyCount() {
        TagDefinition levelDef = store.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("level").type(TagType.FACT).build());
        store.putUserFacts("u1", Map.of("tenant_id", "t1", "level", "活跃"));
        store.putUserFacts("u2", Map.of("tenant_id", "t1", "level", "活跃"));
        store.computeTag(levelDef.getTagId(), ComputeRequest.builder().tenantId("t1").mode("full").build());

        AudienceRequest req = AudienceRequest.builder()
                .tenantId("t1")
                .include(TagQuery.builder()
                        .tenantId("t1")
                        .conditions(List.of(
                                TagQuery.Condition.builder().columnName("level").op("=").value("活跃").build()
                        ))
                        .build())
                .returnIds(false)
                .build();

        AudienceResult result = store.selectAudience(req);
        assertEquals(2, result.getCount());
        assertNull(result.getUserIds());
    }
}