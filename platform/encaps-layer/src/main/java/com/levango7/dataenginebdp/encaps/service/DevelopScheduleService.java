package com.levango7.dataenginebdp.encaps.service;

import com.levango7.dataenginebdp.encaps.model.DevelopScheduleEntity;
import com.levango7.dataenginebdp.encaps.repository.DevelopScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 数据开发调度服务：调度任务持久化 + DAG 依赖解析。
 *
 * <p>调度任务由前端 Web IDE 提交，落库后由调度引擎（stream-batch-scheduler）
 * 按 cron 触发。DAG 解析当前采用「同目录同源表」启发式：
 * <ul>
 *   <li>当前文件作为目标节点</li>
 *   <li>同租户下其他调度任务作为潜在上游</li>
 *   <li>从文件路径推断层级（ods → dwd → dws → ads）形成依赖边</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevelopScheduleService {

    private final DevelopScheduleRepository repository;

    /**
     * 创建调度任务。
     */
    @Transactional
    public DevelopScheduleEntity createSchedule(String filePath, String schedule, String engine,
                                                String tenantId) {
        DevelopScheduleEntity entity = DevelopScheduleEntity.builder()
                .filePath(filePath)
                .schedule(schedule)
                .engine(engine == null ? "spark" : engine)
                .status("active")
                .tenantId(tenantId == null ? "default" : tenantId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        DevelopScheduleEntity saved = repository.save(entity);
        log.info("创建调度任务: id={}, file={}, schedule={}, tenant={}",
                saved.getId(), saved.getFilePath(), saved.getSchedule(), saved.getTenantId());
        return saved;
    }

    /**
     * 列出租户全部调度任务。
     */
    @Transactional(readOnly = true)
    public List<DevelopScheduleEntity> listSchedules(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return repository.findAll();
        }
        return repository.findByTenantId(tenantId);
    }

    /**
     * 解析文件 DAG：当前文件为目标节点，同租户下层级更低的文件作为上游。
     *
     * @param filePath 当前文件路径
     * @param tenantId 租户 ID
     * @return DAG 视图（dagId、nodes、edges）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> resolveDag(String filePath, String tenantId) {
        List<DevelopScheduleEntity> all = listSchedules(tenantId);

        // 当前文件节点
        String currentLayer = inferLayer(filePath);
        Map<String, Object> currentNode = new LinkedHashMap<>();
        currentNode.put("id", filePath);
        currentNode.put("name", inferName(filePath));
        currentNode.put("layer", currentLayer);
        currentNode.put("highlight", true);

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        // 上游：层级低于当前文件的其他调度任务
        for (DevelopScheduleEntity e : all) {
            if (Objects.equals(e.getFilePath(), filePath)) {
                continue;
            }
            String layer = inferLayer(e.getFilePath());
            if (isUpstream(layer, currentLayer)) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", e.getFilePath());
                node.put("name", inferName(e.getFilePath()));
                node.put("layer", layer);
                nodes.add(node);

                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("source", e.getFilePath());
                edge.put("target", filePath);
                edges.add(edge);
            }
        }
        nodes.add(currentNode);

        Map<String, Object> dag = new LinkedHashMap<>();
        dag.put("dagId", "dag-" + Math.abs(filePath.hashCode()));
        dag.put("nodes", nodes);
        dag.put("edges", edges);
        return dag;
    }

    /**
     * 从路径推断层级（ods/dwd/dws/ads）。
     */
    private String inferLayer(String filePath) {
        if (filePath == null) {
            return "unknown";
        }
        String lower = filePath.toLowerCase();
        if (lower.contains("/ods/") || lower.contains("\\ods\\")) {
            return "ods";
        }
        if (lower.contains("/dwd/") || lower.contains("\\dwd\\")) {
            return "dwd";
        }
        if (lower.contains("/dws/") || lower.contains("\\dws\\")) {
            return "dws";
        }
        if (lower.contains("/ads/") || lower.contains("\\ads\\")) {
            return "ads";
        }
        return "other";
    }

    /**
     * 是否为上游层级（数据仓库分层：ods < dwd < dws < ads）。
     */
    private boolean isUpstream(String upstream, String downstream) {
        int u = layerOrder(upstream);
        int d = layerOrder(downstream);
        return u < d;
    }

    private int layerOrder(String layer) {
        switch (layer == null ? "other" : layer) {
            case "ods":
                return 0;
            case "dwd":
                return 1;
            case "dws":
                return 2;
            case "ads":
                return 3;
            default:
                return 4;
        }
    }

    /**
     * 从路径推导节点名（取文件名）。
     */
    private String inferName(String filePath) {
        if (filePath == null) {
            return "unknown";
        }
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        return slash >= 0 ? filePath.substring(slash + 1) : filePath;
    }
}