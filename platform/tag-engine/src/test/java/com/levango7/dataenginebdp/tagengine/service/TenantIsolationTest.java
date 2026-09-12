package com.levango7.dataenginebdp.tagengine.service;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.tagengine.model.AudienceRequest;
import com.levango7.dataenginebdp.tagengine.model.AudienceResult;
import com.levango7.dataenginebdp.tagengine.model.ComputeRequest;
import com.levango7.dataenginebdp.tagengine.model.TagComputeResult;
import com.levango7.dataenginebdp.tagengine.model.TagDefinition;
import com.levango7.dataenginebdp.tagengine.model.TagDefinitionRequest;
import com.levango7.dataenginebdp.tagengine.model.TagQuery;
import com.levango7.dataenginebdp.tagengine.model.TagRule;
import com.levango7.dataenginebdp.tagengine.model.TagRuleRequest;
import com.levango7.dataenginebdp.tagengine.model.TagType;
import com.levango7.dataenginebdp.tagengine.model.UserProfile;
import com.levango7.dataenginebdp.tagengine.repository.TagDefinitionRepository;
import com.levango7.dataenginebdp.tagengine.repository.TagRuleRepository;
import com.levango7.dataenginebdp.tagengine.store.mock.MockTagStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 安全修复：租户隔离与越权防护测试。
 *
 * <p>验证 tag-engine 全模块在注入 {@link TenantContext} 租户过滤后：
 * <ul>
 *   <li>租户 A 无法查询/修改/删除租户 B 的标签定义与规则</li>
 *   <li>租户 A 无法通过 API 参数（请求体 tenantId）越权指定其他租户</li>
 *   <li>租户 A 无法计算/读取租户 B 的画像与人群圈选结果</li>
 * </ul>
 *
 * <p>测试通过手动设置 {@link TenantContext} 模拟不同租户的请求上下文，
 * 对应生产环境中 {@code JwtAuthFilter} 从 JWT claim 解析写入的场景。</p>
 */
@SpringBootTest
@DisplayName("P0 租户隔离与越权防护测试")
class TenantIsolationTest {

    private static final String TENANT_A = "tenant_a";
    private static final String TENANT_B = "tenant_b";

    @Autowired private TagService tagService;
    @Autowired private ComputeService computeService;
    @Autowired private ProfileService profileService;
    @Autowired private AudienceService audienceService;
    @Autowired private MockTagStore mockTagStore;
    @Autowired private TagDefinitionRepository tagDefRepo;
    @Autowired private TagRuleRepository tagRuleRepo;

    @BeforeEach
    void setUp() {
        tagRuleRepo.deleteAll();
        tagDefRepo.deleteAll();
        mockTagStore.clear();
    }

    @AfterEach
    void tearDown() {
        // 必须清理 ThreadLocal，避免线程池复用导致后续测试串号
        TenantContext.clear();
    }

    /** 切换为租户 A 上下文。 */
    private void asTenantA() {
        TenantContext.clear();
        TenantContext.setTenantId(TENANT_A);
        TenantContext.setUserId("user_a");
    }

    /** 切换为租户 B 上下文。 */
    private void asTenantB() {
        TenantContext.clear();
        TenantContext.setTenantId(TENANT_B);
        TenantContext.setUserId("user_b");
    }

    // ==================== 标签定义隔离 ====================

    @Test
    @DisplayName("租户隔离：租户 B 无法查询租户 A 的标签详情")
    void getTagDefinition_crossTenant_shouldReturnEmpty() {
        // 租户 A 创建标签
        asTenantA();
        TagDefinition def = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId(TENANT_A).name("level").type(TagType.FACT).build());
        String tagId = def.getTagId();

        // 租户 B 尝试查询 → 应返回 empty（视为不存在）
        asTenantB();
        Optional<TagDefinition> result = tagService.getTagDefinition(tagId);
        assertTrue(result.isEmpty(), "租户 B 不应能查询到租户 A 的标签");
    }

    @Test
    @DisplayName("租户隔离：listTagDefinitions 仅返回当前租户的标签")
    void listTagDefinitions_shouldOnlyReturnOwnTenant() {
        // 租户 A 创建 2 个标签
        asTenantA();
        tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId(TENANT_A).name("level_a").type(TagType.FACT).build());
        tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId(TENANT_A).name("amount_a").type(TagType.FACT).build());

        // 租户 B 创建 1 个标签
        asTenantB();
        tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId(TENANT_B).name("level_b").type(TagType.FACT).build());

        // 租户 B 列表 → 仅 1 个（自己的）
        List<TagDefinition> bTags = tagService.listTagDefinitions(TENANT_B);
        assertEquals(1, bTags.size(), "租户 B 应仅看到自己的 1 个标签");
        assertEquals("level_b", bTags.get(0).getName());

        // 租户 A 列表 → 仅 2 个（自己的）
        asTenantA();
        List<TagDefinition> aTags = tagService.listTagDefinitions(TENANT_A);
        assertEquals(2, aTags.size(), "租户 A 应仅看到自己的 2 个标签");
    }

    @Test
    @DisplayName("租户隔离：租户 B 无法删除租户 A 的标签")
    void deleteTagDefinition_crossTenant_shouldReturnFalse() {
        // 租户 A 创建标签
        asTenantA();
        TagDefinition def = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId(TENANT_A).name("level").type(TagType.FACT).build());
        String tagId = def.getTagId();

        // 租户 B 尝试删除 → 应返回 false（视为不存在）
        asTenantB();
        boolean deleted = tagService.deleteTagDefinition(tagId);
        assertFalse(deleted, "租户 B 不应能删除租户 A 的标签");

        // 验证标签仍然存在（租户 A 仍可查询）
        asTenantA();
        assertTrue(tagService.getTagDefinition(tagId).isPresent(), "租户 A 的标签不应被删除");
    }

    // ==================== 标签规则隔离 ====================

    @Test
    @DisplayName("租户隔离：租户 B 无法为租户 A 的标签添加规则")
    void createTagRule_crossTenant_shouldThrow() {
        // 租户 A 创建标签
        asTenantA();
        TagDefinition def = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId(TENANT_A).name("level").type(TagType.RULE).build());
        String tagId = def.getTagId();

        // 租户 B 尝试为租户 A 的标签添加规则 → 应抛异常
        asTenantB();
        assertThrows(IllegalArgumentException.class, () ->
                tagService.createTagRule(tagId, TagRuleRequest.builder()
                        .condition("amount >= 5000").value("VIP").priority(10).build()),
                "租户 B 不应能为租户 A 的标签添加规则");
    }

    @Test
    @DisplayName("租户隔离：租户 B 无法列出租户 A 标签的规则")
    void getTagRules_crossTenant_shouldReturnEmpty() {
        // 租户 A 创建标签 + 规则
        asTenantA();
        TagDefinition def = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId(TENANT_A).name("level").type(TagType.RULE).build());
        tagService.createTagRule(def.getTagId(), TagRuleRequest.builder()
                .condition("amount >= 5000").value("VIP").priority(10).build());
        String tagId = def.getTagId();

        // 租户 B 尝试列出规则 → 应返回空
        asTenantB();
        List<TagRule> rules = tagService.getTagRules(tagId);
        assertTrue(rules.isEmpty(), "租户 B 不应能列出租户 A 标签的规则");
    }

    @Test
    @DisplayName("租户隔离：租户 B 无法删除租户 A 的规则")
    void deleteTagRule_crossTenant_shouldReturnFalse() {
        // 租户 A 创建标签 + 规则
        asTenantA();
        TagDefinition def = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId(TENANT_A).name("level").type(TagType.RULE).build());
        TagRule rule = tagService.createTagRule(def.getTagId(), TagRuleRequest.builder()
                .condition("amount >= 5000").value("VIP").priority(10).build());
        String ruleId = rule.getRuleId();

        // 租户 B 尝试删除规则 → 应返回 false
        asTenantB();
        boolean deleted = tagService.deleteTagRule(ruleId);
        assertFalse(deleted, "租户 B 不应能删除租户 A 的规则");

        // 验证规则仍然存在（租户 A 仍可列出）
        asTenantA();
        assertEquals(1, tagService.getTagRules(def.getTagId()).size(), "租户 A 的规则不应被删除");
    }

    // ==================== 越权防护（请求体 tenantId 覆盖） ====================

    @Test
    @DisplayName("越权防护：创建标签时请求体指定其他租户 tenantId，实际创建到当前租户")
    void createTagDefinition_spoofedTenantId_shouldOverrideToCurrent() {
        asTenantA();
        // 请求体恶意指定 tenant_b，但当前上下文是 tenant_a
        TagDefinition def = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId(TENANT_B).name("hijack_attempt").type(TagType.FACT).build());

        // 标签应归属 tenant_a（当前租户），而非 tenant_b
        assertEquals(TENANT_A, def.getTenantId(),
                "创建标签的 tenantId 应被强制覆盖为当前租户，忽略请求体中的恶意值");

        // 验证：租户 B 列表中没有这个标签
        asTenantB();
        List<TagDefinition> bTags = tagService.listTagDefinitions(TENANT_B);
        assertTrue(bTags.stream().noneMatch(t -> t.getTagId().equals(def.getTagId())),
                "被越权创建的标签不应出现在租户 B 的列表中");
    }

    @Test
    @DisplayName("越权防护：圈选时请求体指定其他租户 tenantId，实际用当前租户")
    void selectAudience_spoofedTenantId_shouldOverrideToCurrent() {
        // 租户 A 创建标签并计算画像
        asTenantA();
        TagDefinition def = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId(TENANT_A).name("level").type(TagType.FACT).build());
        mockTagStore.putUserFacts("u_a1", Map.of("tenant_id", TENANT_A, "level", "活跃"));
        computeService.computeTag(def.getTagId(),
                ComputeRequest.builder().tenantId(TENANT_A).mode("full").build());

        // 租户 B 圈选，请求体恶意指定 tenant_a
        asTenantB();
        AudienceRequest req = AudienceRequest.builder()
                .tenantId(TENANT_A)  // 恶意指定租户 A
                .include(TagQuery.builder()
                        .tenantId(TENANT_A)  // 恶意指定租户 A
                        .conditions(List.of(
                                TagQuery.Condition.builder().columnName("level").op("=").value("活跃").build()
                        ))
                        .build())
                .returnIds(true)
                .limit(10)
                .build();

        AudienceResult result = audienceService.selectAudience(req);
        // 租户 B 的圈选结果应为 0（没有租户 B 的画像），而非返回租户 A 的用户
        assertEquals(0, result.getCount(),
                "租户 B 圈选不应返回租户 A 的用户，请求体中的恶意 tenantId 应被忽略");
    }

    @Test
    @DisplayName("越权防护：画像查询时 query 指定其他租户 tenantId，实际用当前租户")
    void queryByTags_spoofedTenantId_shouldOverrideToCurrent() {
        // 租户 A 创建标签并计算画像
        asTenantA();
        TagDefinition def = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId(TENANT_A).name("level").type(TagType.FACT).build());
        mockTagStore.putUserFacts("u_a1", Map.of("tenant_id", TENANT_A, "level", "活跃"));
        computeService.computeTag(def.getTagId(),
                ComputeRequest.builder().tenantId(TENANT_A).mode("full").build());

        // 租户 B 查询，恶意指定 query.tenantId = tenant_a
        asTenantB();
        TagQuery query = TagQuery.builder()
                .tenantId(TENANT_A)  // 恶意指定租户 A
                .conditions(List.of(
                        TagQuery.Condition.builder().columnName("level").op("=").value("活跃").build()
                ))
                .build();

        List<UserProfile> matched = profileService.queryByTags(query);
        assertTrue(matched.isEmpty(),
                "租户 B 的画像查询不应返回租户 A 的用户，query 中的恶意 tenantId 应被忽略");

        long count = profileService.countByTags(query);
        assertEquals(0, count, "租户 B 的计数查询不应统计到租户 A 的用户");
    }

    // ==================== 计算隔离 ====================

    @Test
    @DisplayName("计算隔离：租户 B 计算租户 A 的标签应返回 FAILED")
    void computeTag_crossTenant_shouldReturnFailed() {
        // 租户 A 创建标签
        asTenantA();
        TagDefinition def = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId(TENANT_A).name("level").type(TagType.FACT).build());
        String tagId = def.getTagId();

        // 租户 B 尝试计算租户 A 的标签 → 应返回 FAILED
        asTenantB();
        TagComputeResult result = computeService.computeTag(tagId,
                ComputeRequest.builder().tenantId(TENANT_B).mode("full").build());
        assertEquals("FAILED", result.getStatus(),
                "租户 B 不应能计算租户 A 的标签，应返回 FAILED");
        assertNotNull(result.getErrorMessage(), "FAILED 结果应包含错误信息");
    }

    @Test
    @DisplayName("计算隔离：批量计算时跨租户标签被拒绝，同租户标签正常计算")
    void batchCompute_crossTenant_shouldRejectForeignTagsOnly() {
        // 租户 A 创建标签 a1
        asTenantA();
        TagDefinition a1 = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId(TENANT_A).name("level_a").type(TagType.FACT).build());

        // 租户 B 创建标签 b1
        asTenantB();
        TagDefinition b1 = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId(TENANT_B).name("level_b").type(TagType.FACT).build());
        mockTagStore.putUserFacts("u_b1", Map.of("tenant_id", TENANT_B, "level_b", "活跃"));

        // 租户 B 批量计算 [a1, b1]：a1 应被拒绝，b1 应正常计算
        var result = computeService.batchCompute(List.of(a1.getTagId(), b1.getTagId()),
                ComputeRequest.builder().tenantId(TENANT_B).mode("full").build());

        assertEquals(1, result.getSuccessCount(), "仅租户 B 自己的标签应计算成功");
        assertEquals(1, result.getFailedCount(), "租户 A 的标签应被拒绝");

        // 验证被拒绝的标签结果
        TagComputeResult rejected = result.getResults().stream()
                .filter(r -> "FAILED".equals(r.getStatus()))
                .findFirst()
                .orElseThrow();
        assertEquals(a1.getTagId(), rejected.getTagId(), "被拒绝的应是租户 A 的标签");
    }

    // ==================== 画像读取隔离 ====================

    @Test
    @DisplayName("画像隔离：租户 B 无法读取租户 A 用户的画像")
    void getProfile_crossTenant_shouldReturnNull() {
        // 租户 A 创建标签并计算画像
        asTenantA();
        TagDefinition def = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId(TENANT_A).name("level").type(TagType.FACT).build());
        mockTagStore.putUserFacts("u_a1", Map.of("tenant_id", TENANT_A, "level", "活跃"));
        computeService.computeTag(def.getTagId(),
                ComputeRequest.builder().tenantId(TENANT_A).mode("full").build());

        // 验证租户 A 能读取
        assertNotNull(profileService.getProfile("u_a1", TENANT_A), "租户 A 应能读取自己的用户画像");

        // 租户 B 尝试读取同一用户 → 应返回 null
        asTenantB();
        assertNull(profileService.getProfile("u_a1", TENANT_B),
                "租户 B 不应能读取租户 A 用户的画像");
    }

    // ==================== 缺失上下文防护 ====================

    @Test
    @DisplayName("安全防护：无租户上下文时 Service 方法应抛异常")
    void noTenantContext_shouldThrow() {
        // 确保无租户上下文
        TenantContext.clear();
        assertThrows(IllegalStateException.class,
                () -> tagService.listTagDefinitions(null),
                "无租户上下文时应抛 IllegalStateException，防止未认证请求绕过隔离");
        assertThrows(IllegalStateException.class,
                () -> tagService.createTagDefinition(TagDefinitionRequest.builder()
                        .tenantId("x").name("y").type(TagType.FACT).build()),
                "无租户上下文时创建标签应抛异常");
        assertThrows(IllegalStateException.class,
                () -> profileService.getProfile("any", null),
                "无租户上下文时查询画像应抛异常");
        assertThrows(IllegalStateException.class,
                () -> audienceService.selectAudience(AudienceRequest.builder().build()),
                "无租户上下文时圈选应抛异常");
    }
}