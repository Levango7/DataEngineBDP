package com.shuqing.bigdata.tagengine.service;

import com.shuqing.bigdata.tagengine.model.ComputeRequest;
import com.shuqing.bigdata.tagengine.model.TagDefinition;
import com.shuqing.bigdata.tagengine.model.TagDefinitionRequest;
import com.shuqing.bigdata.tagengine.model.TagQuery;
import com.shuqing.bigdata.tagengine.model.TagType;
import com.shuqing.bigdata.tagengine.model.UserProfile;
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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ProfileService} 集成测试。
 *
 * <p>验证画像查询：单用户画像、按标签条件查询、按标签条件计数。</p>
 */
@SpringBootTest
@DisplayName("ProfileService 画像查询集成测试")
class ProfileServiceTest {

    @Autowired private ProfileService profileService;
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
    @DisplayName("getProfile：存在用户返回完整画像")
    void getProfile_existing_shouldReturnFullProfile() {
        TagDefinition def = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("level").type(TagType.FACT).build());
        mockTagStore.putUserFacts("u1", Map.of("tenant_id", "t1", "level", "活跃"));
        computeService.computeTag(def.getTagId(),
                ComputeRequest.builder().tenantId("t1").mode("full").build());

        UserProfile p = profileService.getProfile("u1");
        assertNotNull(p);
        assertEquals("u1", p.getUserId());
        assertEquals("活跃", p.getTags().get("level"));
    }

    @Test
    @DisplayName("getProfile：不存在返回 null")
    void getProfile_nonExisting_shouldReturnNull() {
        assertNull(profileService.getProfile("nope"));
    }

    @Test
    @DisplayName("queryByTags：AND 条件查询")
    void queryByTags_andConditions_shouldMatch() {
        TagDefinition levelDef = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("level").type(TagType.FACT).build());
        TagDefinition amtDef = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("amount").type(TagType.FACT).build());
        mockTagStore.putUserFacts("u1", Map.of("tenant_id", "t1", "level", "活跃", "amount", 8000));
        mockTagStore.putUserFacts("u2", Map.of("tenant_id", "t1", "level", "活跃", "amount", 3000));
        mockTagStore.putUserFacts("u3", Map.of("tenant_id", "t1", "level", "沉睡", "amount", 100));
        computeService.computeTag(levelDef.getTagId(),
                ComputeRequest.builder().tenantId("t1").mode("full").build());
        computeService.computeTag(amtDef.getTagId(),
                ComputeRequest.builder().tenantId("t1").mode("full").build());

        TagQuery query = TagQuery.builder()
                .tenantId("t1")
                .logic("AND")
                .conditions(List.of(
                        TagQuery.Condition.builder().columnName("level").op("=").value("活跃").build(),
                        TagQuery.Condition.builder().columnName("amount").op(">=").value(5000).build()
                ))
                .build();

        List<UserProfile> matched = profileService.queryByTags(query);
        assertEquals(1, matched.size());
        assertEquals("u1", matched.get(0).getUserId());
    }

    @Test
    @DisplayName("countByTags：单条件计数")
    void countByTags_singleCondition_shouldCount() {
        TagDefinition def = tagService.createTagDefinition(TagDefinitionRequest.builder()
                .tenantId("t1").name("level").type(TagType.FACT).build());
        mockTagStore.putUserFacts("u1", Map.of("tenant_id", "t1", "level", "活跃"));
        mockTagStore.putUserFacts("u2", Map.of("tenant_id", "t1", "level", "活跃"));
        mockTagStore.putUserFacts("u3", Map.of("tenant_id", "t1", "level", "沉睡"));
        computeService.computeTag(def.getTagId(),
                ComputeRequest.builder().tenantId("t1").mode("full").build());

        TagQuery query = TagQuery.builder()
                .tenantId("t1")
                .conditions(List.of(
                        TagQuery.Condition.builder().columnName("level").op("=").value("活跃").build()
                ))
                .build();

        assertEquals(2, profileService.countByTags(query));
    }
}