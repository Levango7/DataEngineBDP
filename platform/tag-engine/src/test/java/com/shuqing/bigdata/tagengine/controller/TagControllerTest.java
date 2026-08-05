package com.shuqing.bigdata.tagengine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.shuqing.bigdata.tagengine.service.AudienceService;
import com.shuqing.bigdata.tagengine.service.ComputeService;
import com.shuqing.bigdata.tagengine.service.ProfileService;
import com.shuqing.bigdata.tagengine.service.TagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全部 Controller 的 MockMvc 测试。
 *
 * <p>使用 standaloneSetup，不启动 Spring 上下文，Service 层用 Mockito Mock。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Tag/Profile/Audience Controller API 端点测试")
class TagControllerTest {

    private MockMvc tagMvc;
    private MockMvc profileMvc;
    private MockMvc audienceMvc;
    private MockMvc healthMvc;

    private final ObjectMapper om = new ObjectMapper();

    @Mock private TagService tagService;
    @Mock private ComputeService computeService;
    @Mock private ProfileService profileService;
    @Mock private AudienceService audienceService;

    @InjectMocks private TagController tagController;
    @InjectMocks private ProfileController profileController;
    @InjectMocks private AudienceController audienceController;
    @InjectMocks private HealthController healthController;

    @BeforeEach
    void setUp() {
        tagMvc = MockMvcBuilders.standaloneSetup(tagController).build();
        profileMvc = MockMvcBuilders.standaloneSetup(profileController).build();
        audienceMvc = MockMvcBuilders.standaloneSetup(audienceController).build();
        healthMvc = MockMvcBuilders.standaloneSetup(healthController).build();
    }

    // ==================== HealthController ====================

    @Test
    @DisplayName("GET /health — 返回 200 与状态")
    void health_shouldReturnUp() throws Exception {
        healthMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.component").value("tag-engine"));
    }

    // ==================== TagController ====================

    @Test
    @DisplayName("POST /api/v1/tags — 创建标签返回 201")
    void createTag_shouldReturn201() throws Exception {
        TagDefinitionRequest req = TagDefinitionRequest.builder()
                .tenantId("t1").name("level").type(TagType.RULE).build();
        TagDefinition def = TagDefinition.builder()
                .tagId("tag-1").tenantId("t1").name("level").type(TagType.RULE)
                .columnName("level").status("ACTIVE")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(tagService.createTagDefinition(any())).thenReturn(def);

        tagMvc.perform(post("/api/v1/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tagId").value("tag-1"))
                .andExpect(jsonPath("$.name").value("level"));
    }

    @Test
    @DisplayName("GET /api/v1/tags?tenantId=t1 — 列出标签返回 200")
    void listTags_shouldReturn200() throws Exception {
        TagDefinition d = TagDefinition.builder().tagId("tag-1").tenantId("t1").name("a").build();
        when(tagService.listTagDefinitions("t1")).thenReturn(List.of(d));

        tagMvc.perform(get("/api/v1/tags").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/tags/{id} — 存在返回 200")
    void getTag_existing_shouldReturn200() throws Exception {
        TagDefinition d = TagDefinition.builder().tagId("tag-1").name("x").build();
        when(tagService.getTagDefinition("tag-1")).thenReturn(Optional.of(d));

        tagMvc.perform(get("/api/v1/tags/tag-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("x"));
    }

    @Test
    @DisplayName("GET /api/v1/tags/{id} — 不存在返回 404")
    void getTag_nonExisting_shouldReturn404() throws Exception {
        when(tagService.getTagDefinition("nope")).thenReturn(Optional.empty());
        tagMvc.perform(get("/api/v1/tags/nope")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/v1/tags/{id} — 存在返回 204")
    void deleteTag_existing_shouldReturn204() throws Exception {
        when(tagService.deleteTagDefinition("tag-1")).thenReturn(true);
        tagMvc.perform(delete("/api/v1/tags/tag-1")).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /api/v1/tags/{id} — 不存在返回 404")
    void deleteTag_nonExisting_shouldReturn404() throws Exception {
        when(tagService.deleteTagDefinition("nope")).thenReturn(false);
        tagMvc.perform(delete("/api/v1/tags/nope")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/tags/{id}/rules — 添加规则返回 201")
    void addRule_shouldReturn201() throws Exception {
        TagRuleRequest req = TagRuleRequest.builder()
                .condition("amount >= 5000").value("活跃").priority(10).build();
        TagRule rule = TagRule.builder().ruleId("rule-1").tagId("tag-1").value("活跃").priority(10).build();
        when(tagService.createTagRule(eq("tag-1"), any())).thenReturn(rule);

        tagMvc.perform(post("/api/v1/tags/tag-1/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleId").value("rule-1"));
    }

    @Test
    @DisplayName("GET /api/v1/tags/{id}/rules — 列出规则返回 200")
    void listRules_shouldReturn200() throws Exception {
        TagRule r = TagRule.builder().ruleId("rule-1").tagId("tag-1").priority(5).build();
        when(tagService.getTagRules("tag-1")).thenReturn(List.of(r));

        tagMvc.perform(get("/api/v1/tags/tag-1/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/tags/{id}/compute — 计算标签返回 200")
    void computeTag_shouldReturn200() throws Exception {
        ComputeRequest req = ComputeRequest.builder().tenantId("t1").mode("full").build();
        TagComputeResult result = TagComputeResult.builder()
                .tagId("tag-1").status("SUCCESS").affectedRows(100).build();
        when(computeService.computeTag(eq("tag-1"), any())).thenReturn(result);

        tagMvc.perform(post("/api/v1/tags/tag-1/compute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.affectedRows").value(100));
    }

    @Test
    @DisplayName("POST /api/v1/tags/batch-compute — 批量计算返回 200")
    void batchCompute_shouldReturn200() throws Exception {
        TagController.BatchComputeBody body = new TagController.BatchComputeBody(
                List.of("tag-1", "tag-2"),
                ComputeRequest.builder().tenantId("t1").mode("full").build());
        BatchComputeResult result = BatchComputeResult.builder()
                .results(List.of(
                        TagComputeResult.builder().tagId("tag-1").status("SUCCESS").build(),
                        TagComputeResult.builder().tagId("tag-2").status("SUCCESS").build()))
                .successCount(2).failedCount(0).totalCostMs(50).build();
        when(computeService.batchCompute(any(), any())).thenReturn(result);

        tagMvc.perform(post("/api/v1/tags/batch-compute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(2))
                .andExpect(jsonPath("$.failedCount").value(0));
    }

    // ==================== ProfileController ====================

    @Test
    @DisplayName("GET /api/v1/profiles/{userId} — 存在返回 200")
    void getProfile_existing_shouldReturn200() throws Exception {
        UserProfile p = UserProfile.builder()
                .userId("u1").tenantId("t1").tags(Map.of("level", "活跃")).build();
        when(profileService.getProfile("u1")).thenReturn(p);

        profileMvc.perform(get("/api/v1/profiles/u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("u1"))
                .andExpect(jsonPath("$.tags.level").value("活跃"));
    }

    @Test
    @DisplayName("GET /api/v1/profiles/{userId} — 不存在返回 404")
    void getProfile_nonExisting_shouldReturn404() throws Exception {
        when(profileService.getProfile("nope")).thenReturn(null);
        profileMvc.perform(get("/api/v1/profiles/nope")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/profiles/query — 按标签查询返回 200")
    void queryByTags_shouldReturn200() throws Exception {
        TagQuery query = TagQuery.builder().tenantId("t1").build();
        UserProfile p = UserProfile.builder().userId("u1").tenantId("t1").build();
        when(profileService.queryByTags(any())).thenReturn(List.of(p));

        profileMvc.perform(post("/api/v1/profiles/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(query)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/profiles/count — 统计人数返回 200")
    void countByTags_shouldReturn200() throws Exception {
        TagQuery query = TagQuery.builder().tenantId("t1").build();
        when(profileService.countByTags(any())).thenReturn(42L);

        profileMvc.perform(post("/api/v1/profiles/count")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(query)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(42));
    }

    // ==================== AudienceController ====================

    @Test
    @DisplayName("POST /api/v1/audiences/select — 人群圈选返回 200")
    void selectAudience_shouldReturn200() throws Exception {
        AudienceRequest req = AudienceRequest.builder()
                .tenantId("t1").returnIds(true).limit(10).build();
        AudienceResult result = AudienceResult.builder()
                .count(5).userIds(List.of("u1", "u2")).truncated(false).costMs(12).build();
        when(audienceService.selectAudience(any())).thenReturn(result);

        audienceMvc.perform(post("/api/v1/audiences/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(5))
                .andExpect(jsonPath("$.userIds.length()").value(2));
    }
}