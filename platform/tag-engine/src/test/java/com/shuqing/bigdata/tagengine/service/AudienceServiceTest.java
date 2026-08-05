package com.shuqing.bigdata.tagengine.service;

import com.shuqing.bigdata.tagengine.model.AudienceRequest;
import com.shuqing.bigdata.tagengine.model.AudienceResult;
import com.shuqing.bigdata.tagengine.model.ComputeRequest;
import com.shuqing.bigdata.tagengine.model.TagDefinition;
import com.shuqing.bigdata.tagengine.model.TagDefinitionRequest;
import com.shuqing.bigdata.tagengine.model.TagQuery;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AudienceService} 集成测试。
 *
 * <p>验证人群圈选：include/exclude、returnIds、limit 截断、租户隔离。</p>
 */
@SpringBootTest
@DisplayName("AudienceService 人群圈选集成测试")
class AudienceServiceTest {

    @Autowired private AudienceService audienceService;
    @Autowired private TagService tagService;
    @Autowired private ComputeService computeService;
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
    @DisplayName("圈选：include 命中 + returnIds 返回 ID 列表")
    void selectAudience_includeAndReturnIds_shouldReturnIds() {
        TagDefinition def = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("level").type(TagType.FACT).build());
        mockTagStore.putUserFacts("u1", Map.of("tenant_id", "t1", "level", "活跃"));
        mockTagStore.putUserFacts("u2", Map.of("tenant_id", "t1", "level", "活跃"));
        mockTagStore.putUserFacts("u3", Map.of("tenant_id", "t1", "level", "沉睡"));
        computeService.computeTag(def.getTagId(),
                ComputeRequest.builder().tenantId("t1").mode("full").build());

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

        AudienceResult result = audienceService.selectAudience(req);
        assertEquals(2, result.getCount());
        assertNotNull(result.getUserIds());
        assertEquals(2, result.getUserIds().size());
        assertFalse(result.isTruncated());
    }

    @Test
    @DisplayName("圈选：exclude 剔除命中用户")
    void selectAudience_exclude_shouldRemoveMatched() {
        TagDefinition levelDef = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("level").type(TagType.FACT).build());
        TagDefinition riskDef = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("risk").type(TagType.FACT).build());
        mockTagStore.putUserFacts("u1", Map.of("tenant_id", "t1", "level", "活跃", "risk", "高"));
        mockTagStore.putUserFacts("u2", Map.of("tenant_id", "t1", "level", "活跃", "risk", "低"));
        mockTagStore.putUserFacts("u3", Map.of("tenant_id", "t1", "level", "沉睡", "risk", "低"));
        computeService.computeTag(levelDef.getTagId(),
                ComputeRequest.builder().tenantId("t1").mode("full").build());
        computeService.computeTag(riskDef.getTagId(),
                ComputeRequest.builder().tenantId("t1").mode("full").build());

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

        AudienceResult result = audienceService.selectAudience(req);
        assertEquals(1, result.getCount());
        assertEquals("u2", result.getUserIds().get(0));
    }

    @Test
    @DisplayName("圈选：limit 截断标记 truncated=true")
    void selectAudience_limitTruncation_shouldMarkTruncated() {
        TagDefinition def = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("level").type(TagType.FACT).build());
        mockTagStore.putUserFacts("u1", Map.of("tenant_id", "t1", "level", "活跃"));
        mockTagStore.putUserFacts("u2", Map.of("tenant_id", "t1", "level", "活跃"));
        mockTagStore.putUserFacts("u3", Map.of("tenant_id", "t1", "level", "活跃"));
        computeService.computeTag(def.getTagId(),
                ComputeRequest.builder().tenantId("t1").mode("full").build());

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

        AudienceResult result = audienceService.selectAudience(req);
        assertEquals(3, result.getCount());
        assertEquals(2, result.getUserIds().size());
        assertTrue(result.isTruncated());
    }

    @Test
    @DisplayName("圈选：returnIds=false 仅返回 count")
    void selectAudience_noReturnIds_shouldOnlyCount() {
        TagDefinition def = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("level").type(TagType.FACT).build());
        mockTagStore.putUserFacts("u1", Map.of("tenant_id", "t1", "level", "活跃"));
        mockTagStore.putUserFacts("u2", Map.of("tenant_id", "t1", "level", "活跃"));
        computeService.computeTag(def.getTagId(),
                ComputeRequest.builder().tenantId("t1").mode("full").build());

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

        AudienceResult result = audienceService.selectAudience(req);
        assertEquals(2, result.getCount());
    }

    @Test
    @DisplayName("圈选：租户隔离 — 不返回其他租户用户")
    void selectAudience_tenantIsolation_shouldOnlyReturnOwnTenant() {
        TagDefinition def = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("level").type(TagType.FACT).build());
        mockTagStore.putUserFacts("u1", Map.of("tenant_id", "t1", "level", "活跃"));
        mockTagStore.putUserFacts("u2", Map.of("tenant_id", "t2", "level", "活跃"));
        computeService.computeTag(def.getTagId(),
                ComputeRequest.builder().tenantId("t1").mode("full").build());

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

        AudienceResult result = audienceService.selectAudience(req);
        assertEquals(1, result.getCount());
        assertEquals("u1", result.getUserIds().get(0));
    }
}