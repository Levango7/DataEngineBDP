package com.levango7.dataenginebdp.tagengine.service;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.tagengine.model.TagQuery;
import com.levango7.dataenginebdp.tagengine.model.UserProfile;
import com.levango7.dataenginebdp.tagengine.store.TagStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

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
     * 按标签条件查询用户列表（租户隔离：tenantId 强制取当前租户）。
     *
     * <p>R8 租户隔离补齐：查询条件里的 {@code tenantId} 一律被当前租户覆盖，
     * 防止调用方（含非 HTTP 调用方）用别的租户 ID 捞画像。</p>
     *
     * @param query 标签查询条件
     * @return 命中用户画像列表（仅当前租户）
     * @throws IllegalStateException 缺少租户上下文（fail-closed）
     */
    public List<UserProfile> queryByTags(TagQuery query) {
        query.setTenantId(requireTenant());
        return tagStore.queryByTags(query);
    }

    /**
     * 按标签条件统计用户数（租户隔离：tenantId 强制取当前租户）。
     *
     * @param query 标签查询条件
     * @return 命中用户数（仅当前租户）
     * @throws IllegalStateException 缺少租户上下文（fail-closed）
     */
    public long countByTags(TagQuery query) {
        query.setTenantId(requireTenant());
        return tagStore.countByTags(query);
    }

    /**
     * 取当前租户 ID；缺失时 fail-closed 抛异常。
     *
     * <p>Service 层不信任入参/请求体里的 tenantId，一律以 {@link TenantContext} 为准。</p>
     *
     * @return 当前租户 ID
     * @throws IllegalStateException 租户上下文缺失
     */
    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("ProfileService: 缺少租户上下文，拒绝查询");
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }
}