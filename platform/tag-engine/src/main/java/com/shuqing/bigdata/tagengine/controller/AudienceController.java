package com.shuqing.bigdata.tagengine.controller;

import com.shuqing.bigdata.tagengine.model.AudienceRequest;
import com.shuqing.bigdata.tagengine.model.AudienceResult;
import com.shuqing.bigdata.tagengine.service.AudienceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人群圈选 REST 控制器。
 *
 * <p>统一前缀：{@code /api/v1/audiences}</p>
 * <ul>
 *   <li>POST /select — 人群圈选，返回 count 与可选 user_id 列表</li>
 * </ul>
 *
 * <p>对应详细设计 §5 人群圈选、§6 接口 {@code POST /api/tag/v1/segment}。</p>
 */
@RestController
@RequestMapping("/api/v1/audiences")
public class AudienceController {

    private final AudienceService audienceService;

    public AudienceController(AudienceService audienceService) {
        this.audienceService = audienceService;
    }

    /**
     * 人群圈选。
     *
     * @param req 圈选请求
     * @return 圈选结果
     */
    @PostMapping("/select")
    public ResponseEntity<AudienceResult> select(@RequestBody AudienceRequest req) {
        return ResponseEntity.ok(audienceService.selectAudience(req));
    }
}