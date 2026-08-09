package com.levango7.dataenginebdp.encaps.security.facade.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.encaps.security.facade.config.SecurityFacadeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 证据归档器（EvidenceArchive）。
 *
 * <p>将 {@link EvidenceItem} 持久化到文件系统，作为合规证据的长期留存载体。</p>
 *
 * <h3>归档策略</h3>
 * <ul>
 *   <li><b>目录结构</b>：{@code <archive-dir>/<yyyy-MM-dd>/<evidence-id>.json}，
 *       按日期分目录便于按时间检索与清理</li>
 *   <li><b>校验和</b>：每条证据计算 SHA-256 校验和并写入内容，
 *       归档后可重新计算验证完整性，防篡改</li>
 *   <li><b>JSON 格式</b>：使用 Jackson 序列化，便于人工审阅与第三方工具解析</li>
 *   <li><b>原子写入</b>：先写临时文件再 rename，避免半截文件</li>
 * </ul>
 *
 * <h3>等保对应</h3>
 * <p>对应 GB/T 22239-2019 8.1.4.3 c) "保护审计记录，避免未预期的删除、修改或覆盖"：
 * 校验和机制可检测篡改；按日期分目录避免单文件覆盖。</p>
 */
@Component
public class EvidenceArchive {

    private static final Logger log = LoggerFactory.getLogger(EvidenceArchive.class);
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SecurityFacadeConfig config;
    private final ObjectMapper objectMapper;

    /**
     * 构造 EvidenceArchive。
     *
     * @param config       SecurityFacade 配置
     * @param objectMapper Jackson ObjectMapper
     */
    public EvidenceArchive(SecurityFacadeConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /**
     * 归档单条证据。
     *
     * @param item 证据项（checksum 可空，归档时填充）
     * @return 归档后的证据项（含 checksum）
     * @throws IOException 写入失败
     */
    public EvidenceItem archive(EvidenceItem item) throws IOException {
        // 计算校验和
        String checksum = computeChecksum(item);
        EvidenceItem withChecksum = new EvidenceItem(
                item.getId(),
                item.getType(),
                item.getTimestamp(),
                item.getDescription(),
                item.getSource(),
                item.getContent(),
                checksum);

        // 写入文件
        Path target = resolveTargetPath(item);
        Files.createDirectories(target.getParent());
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(withChecksum.toMap());

        // 原子写入：先写 .tmp 再 rename
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, json,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        log.info("Archived evidence {} to {}", item.getId(), target);
        return withChecksum;
    }

    /**
     * 批量归档。
     *
     * @param items 证据项列表
     * @return 归档后的证据项列表（含 checksum）
     * @throws IOException 写入失败
     */
    public List<EvidenceItem> archiveAll(List<EvidenceItem> items) throws IOException {
        List<EvidenceItem> archived = new ArrayList<>(items.size());
        for (EvidenceItem item : items) {
            archived.add(archive(item));
        }
        log.info("Archived {} evidence items to {}", items.size(), config.getEvidence().getArchiveDir());
        return archived;
    }

    /**
     * 列出指定日期目录下的所有证据文件路径。
     *
     * @param day 日期字符串（yyyy-MM-dd）
     * @return 文件路径列表
     * @throws IOException 读取失败
     */
    public List<Path> listByDay(String day) throws IOException {
        Path dayDir = config.getEvidence().getArchivePath().resolve(day);
        if (!Files.exists(dayDir)) {
            return List.of();
        }
        try (var stream = Files.list(dayDir)) {
            return stream.filter(p -> p.toString().endsWith(".json")).toList();
        }
    }

    /**
     * 验证已归档证据的校验和。
     *
     * @param path 证据文件路径
     * @return 校验通过返回 true
     * @throws IOException 读取失败
     */
    public boolean verify(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = objectMapper.readValue(bytes, Map.class);
        String storedChecksum = (String) map.get("checksum");
        if (storedChecksum == null) {
            return false;
        }
        // 重新计算：将 checksum 字段置空后序列化
        map.put("checksum", null);
        byte[] contentBytes = objectMapper.writeValueAsBytes(map);
        String recomputed = sha256Hex(contentBytes);
        return storedChecksum.equals(recomputed);
    }

    // ===== 内部 =====

    private Path resolveTargetPath(EvidenceItem item) {
        String day = item.getTimestamp().atZone(java.time.ZoneOffset.UTC).toLocalDate().format(DAY_FMT);
        return config.getEvidence().getArchivePath()
                .resolve(day)
                .resolve(item.getId() + ".json");
    }

    private String computeChecksum(EvidenceItem item) {
        // 将 checksum 字段置空后序列化，计算 SHA-256
        Map<String, Object> map = new java.util.LinkedHashMap<>(item.toMap());
        map.put("checksum", null);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(map);
            return sha256Hex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute checksum", e);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}