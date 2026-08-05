package com.shuqing.bigdata.tagengine.controller;

import com.shuqing.bigdata.tagengine.model.TagQuery;
import com.shuqing.bigdata.tagengine.model.UserProfile;
import com.shuqing.bigdata.tagengine.service.ProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户画像查询 REST 控制器。
 *
 * <p>统一前缀：{@code /api/v1/profiles}</p>
 * <ul>
 *   <li>GET  /{userId}     — 单用户画像，返回 200 或 404</li>
 *   <li>POST /query        — 按标签条件查询用户列表，返回 200</li>
 *   <li>POST /count        — 按标签条件统计人数，返回 200</li>
 * </ul>
 *
 * <p>对应详细设计 §6 接口 {@code GET /api/tag/v1/portrait/{userId}}。</p>
 */
@RestController
@RequestMapping("/api/v1/profiles")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * 获取单用户画像。
     *
     * @param userId 用户 ID
     * @return 用户画像
     */
    @GetMapping("/{userId}")
    public ResponseEntity<UserProfile> getProfile(@PathVariable String userId) {
        UserProfile profile = profileService.getProfile(userId);
        if (profile == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(profile);
    }

    /**
     * 按标签条件查询用户列表。
     *
     * @param query 标签查询条件
     * @return 命中用户画像列表
     */
    @PostMapping("/query")
    public ResponseEntity<List<UserProfile>> queryByTags(@RequestBody TagQuery query) {
        return ResponseEntity.ok(profileService.queryByTags(query));
    }

    /**
     * 按标签条件统计人数。
     *
     * @param query 标签查询条件
     * @return 命中用户数
     */
    @PostMapping("/count")
    public ResponseEntity<CountResponse> countByTags(@RequestBody TagQuery query) {
        long count = profileService.countByTags(query);
        return ResponseEntity.status(HttpStatus.OK).body(new CountResponse(count));
    }

    /**
     * 计数响应体。
     *
     * @param count 命中用户数
     */
    public record CountResponse(long count) {
    }
}