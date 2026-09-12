package com.levango7.dataenginebdp.tagengine.service;

import com.levango7.dataenginebdp.tagengine.model.TagQuery;
import com.levango7.dataenginebdp.tagengine.model.UserProfile;
import com.levango7.dataenginebdp.tagengine.store.TagStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 画像查询服务。
 *
 * <p>提供单用户画像与按标签条件批量查询用户画像的能力。
 * 对应详细设计 §6 接口 {@code GET /api/tag/v1/portrait/{userId}}。</p>
 */
@Service
public class ProfileService {

    private final TagStore tagStore;

    public ProfileService(TagStore tagStore) {
        this.tagStore = tagStore;
    }

    /**
     * 获取单个用户画像。
     *
     * <p>R13 安全修复：添加 tenantId 参数实现租户隔离，tenantId 由 Controller 从 JWT
     * （TenantContext）获取并显式传入，Service/Store 据此过滤，不信任客户端请求。</p>
     *
     * @param userId   用户 ID
     * @param tenantId 租户 ID（来自 JWT，不可为 null/空）
     * @return 用户画像；不存在或不属于该租户返回 null
     * @throws IllegalStateException 若 tenantId 为 null/空（fail-closed）
     */
    public UserProfile getProfile(String userId, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tagStore.getProfile(userId, tenantId);
    }

    /**
     * 按标签条件查询用户列表。
     *
     * @param query 标签查询条件
     * @return 命中用户画像列表
     */
    public List<UserProfile> queryByTags(TagQuery query) {
        return tagStore.queryByTags(query);
    }

    /**
     * 按标签条件统计用户数。
     *
     * @param query 标签查询条件
     * @return 命中用户数
     */
    public long countByTags(TagQuery query) {
        return tagStore.countByTags(query);
    }
}