package com.levango7.dataenginebdp.tagengine.store;

import com.levango7.dataenginebdp.tagengine.model.AudienceRequest;
import com.levango7.dataenginebdp.tagengine.model.AudienceResult;
import com.levango7.dataenginebdp.tagengine.model.BatchComputeResult;
import com.levango7.dataenginebdp.tagengine.model.ComputeRequest;
import com.levango7.dataenginebdp.tagengine.model.TagComputeResult;
import com.levango7.dataenginebdp.tagengine.model.TagDefinition;
import com.levango7.dataenginebdp.tagengine.model.TagDefinitionRequest;
import com.levango7.dataenginebdp.tagengine.model.TagQuery;
import com.levango7.dataenginebdp.tagengine.model.TagRule;
import com.levango7.dataenginebdp.tagengine.model.TagRuleRequest;
import com.levango7.dataenginebdp.tagengine.model.UserProfile;

import java.util.List;

/**
 * 标签存储抽象接口。
 *
 * <p>标签画像引擎通过本接口屏蔽底层存储差异：</p>
 * <ul>
 *   <li>{@code MockTagStore}  — 内存实现，用于开发/测试与无 Doris 环境的 Mock 模式</li>
 *   <li>{@code DorisTagStore} — 真实 Doris 集群实现，通过 JDBC 调用 Doris FE</li>
 * </ul>
 *
 * <p>真实环境通过 {@code app.tag-store.type=doris} 配置切换实现，
 * Helm Chart 注入 Doris FE 地址与凭据。对应详细设计 §2 总体架构、§8 多环境适配。</p>
 *
 * <p>所有方法均需保证租户隔离：调用方传入的 tenantId 与对象自身 tenantId 必须一致，
 * 实现层应再次校验，防止越权跨租户读写。</p>
 */
public interface TagStore {

    // ==================== 标签定义管理 ====================

    /**
     * 创建标签定义。
     *
     * @param req 创建请求
     * @return 已落地的标签定义（含生成的 tagId 与时间戳）
     */
    TagDefinition createTagDefinition(TagDefinitionRequest req);

    /**
     * 按 ID 获取标签定义。
     *
     * @param tagId 标签 ID
     * @return 标签定义；不存在返回 null
     */
    TagDefinition getTagDefinition(String tagId);

    /**
     * 列出指定租户的全部标签定义。
     *
     * @param tenantId 租户 ID
     * @return 标签定义列表（不会返回 null）
     */
    List<TagDefinition> listTagDefinitions(String tenantId);

    /**
     * 删除标签定义（同时删除其下全部规则与宽表列）。
     *
     * @param tagId 标签 ID
     * @return true 表示存在并已删除；false 表示 ID 不存在
     */
    boolean deleteTagDefinition(String tagId);

    // ==================== 标签规则管理 ====================

    /**
     * 为指定标签添加规则。
     *
     * @param tagId 标签 ID
     * @param req   规则创建请求
     * @return 已落地的标签规则
     */
    TagRule createTagRule(String tagId, TagRuleRequest req);

    /**
     * 列出指定标签的全部规则（按 priority 降序）。
     *
     * @param tagId 标签 ID
     * @return 规则列表
     */
    List<TagRule> getTagRules(String tagId);

    /**
     * 删除规则。
     *
     * @param ruleId 规则 ID
     * @return true 表示存在并已删除
     */
    boolean deleteTagRule(String ruleId);

    // ==================== 标签计算 ====================

    /**
     * 计算单个标签，将结果写入宽表（Mock 模式写入内存画像）。
     *
     * @param tagId 标签 ID
     * @param req   计算请求
     * @return 计算结果
     */
    TagComputeResult computeTag(String tagId, ComputeRequest req);

    /**
     * 批量计算多个标签。
     *
     * @param tagIds 标签 ID 列表
     * @param req    计算请求
     * @return 批量计算结果
     */
    BatchComputeResult batchCompute(List<String> tagIds, ComputeRequest req);

    // ==================== 画像查询 ====================

    /**
     * 获取单个用户的画像（全量标签值）。
     *
     * @param userId 用户 ID
     * @return 用户画像；不存在返回 null
     */
    UserProfile getProfile(String userId);

    /**
     * 按标签条件查询用户列表。
     *
     * @param query 标签查询条件
     * @return 命中用户画像列表
     */
    List<UserProfile> queryByTags(TagQuery query);

    /**
     * 按标签条件统计用户数。
     *
     * @param query 标签查询条件
     * @return 命中用户数
     */
    long countByTags(TagQuery query);

    // ==================== 人群圈选 ====================

    /**
     * 人群圈选。
     *
     * @param req 圈选请求
     * @return 圈选结果
     */
    AudienceResult selectAudience(AudienceRequest req);
}