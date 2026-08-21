package com.shuqing.bigdata.tagengine.service;

import com.shuqing.bigdata.tagengine.model.ComputeRequest;
import com.shuqing.bigdata.tagengine.model.TagComputeResult;
import com.shuqing.bigdata.tagengine.model.TagDefinition;
import com.shuqing.bigdata.tagengine.model.TagDefinitionRequest;
import com.shuqing.bigdata.tagengine.model.TagRuleRequest;
import com.shuqing.bigdata.tagengine.model.TagType;
import com.shuqing.bigdata.tagengine.repository.TagDefinitionRepository;
import com.shuqing.bigdata.tagengine.repository.TagRuleRepository;
import com.shuqing.bigdata.tagengine.store.mock.MockTagStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link ComputeService} 集成测试。
 *
 * <p>启动完整 Spring 上下文（Mock 模式），验证标签计算端到端流程：
 * 定义标签 → 添加规则 → 注入事实数据 → 触发计算 → 校验画像。</p>
 */
@SpringBootTest
@DisplayName("ComputeService 标签计算集成测试")
class ComputeServiceTest {

    @Autowired private ComputeService computeService;
    @Autowired private TagService tagService;
    @Autowired private MockTagStore mockTagStore;
    @Autowired private TagDefinitionRepository tagDefRepo;
    @Autowired private TagRuleRepository tagRuleRepo;

    @BeforeEach
    void setUp() {
        tagRuleRepo.deleteAll();
        tagDefRepo.deleteAll();
        mockTagStore.clear();
    }

    @Test
    @DisplayName("规则标签端到端计算：定义→规则→事实→计算→画像")
    void computeTag_endToEnd_shouldProduceProfile() {
        // 1. 定义规则标签 user_level
        TagDefinition def = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("user_level").type(TagType.RULE)
                .valueDomain(List.of("新客", "活跃", "沉睡")).build());

        // 2. 添加规则
        tagService.createTagRule(def.getTagId(), TagRuleRequest.builder()
                .condition("total_amount >= 5000").value("活跃").priority(10).build());
        tagService.createTagRule(def.getTagId(), TagRuleRequest.builder()
                .condition("total_amount >= 100").value("新客").priority(5).build());

        // 3. 注入事实数据
        mockTagStore.putUserFacts("u1", Map.of("tenant_id", "t1", "total_amount", 8000));
        mockTagStore.putUserFacts("u2", Map.of("tenant_id", "t1", "total_amount", 200));

        // 4. 触发计算
        TagComputeResult result = computeService.computeTag(def.getTagId(),
                ComputeRequest.builder().tenantId("t1").mode("full").build());

        // 5. 校验
        assertEquals("SUCCESS", result.getStatus());
        assertEquals(2, result.getAffectedRows());
        assertNotNull(result.getTagVersion());
        assertEquals("活跃", mockTagStore.getProfile("u1").getTags().get("user_level"));
        assertEquals("新客", mockTagStore.getProfile("u2").getTags().get("user_level"));
    }

    @Test
    @DisplayName("批量计算：多标签一次提交")
    void batchCompute_multipleTags_shouldAggregate() {
        TagDefinition d1 = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("amount").type(TagType.FACT).build());
        TagDefinition d2 = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("days").type(TagType.FACT).build());

        mockTagStore.putUserFacts("u1", Map.of("tenant_id", "t1", "amount", 100, "days", 30));
        mockTagStore.putUserFacts("u2", Map.of("tenant_id", "t1", "amount", 200, "days", 60));

        var result = computeService.batchCompute(List.of(d1.getTagId(), d2.getTagId()),
                ComputeRequest.builder().tenantId("t1").mode("full").build());

        assertEquals(2, result.getSuccessCount());
        assertEquals(0, result.getFailedCount());
        assertEquals(2, result.getResults().size());
    }

    @Test
    @DisplayName("批量计算：空列表返回空结果")
    void batchCompute_emptyList_shouldReturnEmpty() {
        var result = computeService.batchCompute(List.of(),
                ComputeRequest.builder().tenantId("t1").mode("full").build());
        assertEquals(0, result.getSuccessCount());
        assertEquals(0, result.getResults().size());
    }
}