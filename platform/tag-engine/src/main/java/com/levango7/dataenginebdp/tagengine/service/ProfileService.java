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
     * @param userId 用户 ID
     * @return 用户画像；不存在返回 null
     */
    public UserProfile getProfile(String userId) {
        return tagStore.getProfile(userId);
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