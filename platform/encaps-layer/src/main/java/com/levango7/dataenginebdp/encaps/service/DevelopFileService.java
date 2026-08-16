package com.levango7.dataenginebdp.encaps.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据开发文件服务：扫描工作空间目录文件树、读取文件内容。
 *
 * <p>工作空间根路径由配置 {@code app.develop.workspace-root} 注入，
 * 默认 {@code ./data/develop-workspace}。仅扫描受信任的根目录下的文件，
 * 路径穿越（{@code ..}）会被规范化后校验，避免越权读取。</p>
 */
@Slf4j
@Service
public class DevelopFileService {

    /** 工作空间根路径（绝对化）。 */
    private final Path workspaceRoot;

    /** 最大递归深度，避免目录嵌套过深导致栈溢出。 */
    private static final int MAX_DEPTH = 8;

    /** 单文件最大读取字节数（4MB），避免读取超大文件导致 OOM。 */
    private static final long MAX_FILE_SIZE = 4L * 1024 * 1024;

    public DevelopFileService(@Value("${app.develop.workspace-root:./data/develop-workspace}") String root) {
        this.workspaceRoot = Paths.get(root).toAbsolutePath().normalize();
        log.info("数据开发工作空间根路径: {}", workspaceRoot);
    }

    /**
     * 扫描工作空间文件树。
     *
     * @return 文件树节点列表（根目录下的直接子节点；folder 含 children）
     */
    public List<Map<String, Object>> getFileTree() {
        if (!Files.exists(workspaceRoot)) {
            try {
                Files.createDirectories(workspaceRoot);
                log.info("工作空间根目录不存在，已自动创建: {}", workspaceRoot);
            } catch (IOException e) {
                log.error("无法创建工作空间根目录: {}", workspaceRoot, e);
                return List.of();
            }
        }
        List<Map<String, Object>> tree = new ArrayList<>();
        try {
            Files.walkFileTree(workspaceRoot, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                    MAX_DEPTH, new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (dir.equals(workspaceRoot)) {
                                return FileVisitResult.CONTINUE;
                            }
                            // 跳过隐藏目录（如 .git、.idea）
                            if (isHidden(dir)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            tree.add(toNode(dir, true));
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (isHidden(file)) {
                                return FileVisitResult.CONTINUE;
                            }
                            tree.add(toNode(file, false));
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            log.error("扫描工作空间文件树失败: {}", workspaceRoot, e);
            return List.of();
        }
        // 排序：文件夹优先，再按名称升序
        tree.sort(Comparator
                .comparing((Map<String, Object> n) -> "folder".equals(n.get("type")) ? 0 : 1)
                .thenComparing(n -> String.valueOf(n.get("name"))));
        return tree;
    }

    /**
     * 读取文件内容。
     *
     * @param relativePath 相对工作空间根的路径
     * @return 文件内容字符串
     * @throws IllegalArgumentException 路径穿越或文件不存在
     * @throws IllegalStateException    文件过大或读取失败
     */
    public String readFile(String relativePath) {
        Path target = resolveAndValidate(relativePath);
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("文件不存在: " + relativePath);
        }
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("不是常规文件: " + relativePath);
        }
        try {
            long size = Files.size(target);
            if (size > MAX_FILE_SIZE) {
                throw new IllegalStateException("文件过大（" + size + "B > " + MAX_FILE_SIZE + "B）: " + relativePath);
            }
            return Files.readString(target);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败: " + relativePath, e);
        }
    }

    /**
     * 解析并校验路径：规范化后必须仍位于工作空间根下。
     */
    private Path resolveAndValidate(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        Path resolved = workspaceRoot.resolve(relativePath).toAbsolutePath().normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("路径穿越被拒绝: " + relativePath);
        }
        return resolved;
    }

    /**
     * 构造文件树节点。
     *
     * @param path    文件/目录路径
     * @param isFolder 是否为目录
     * @return 节点 Map
     */
    private Map<String, Object> toNode(Path path, boolean isFolder) {
        Map<String, Object> node = new LinkedHashMap<>();
        String relative = workspaceRoot.relativize(path).toString().replace('\\', '/');
        node.put("id", relative);
        node.put("name", path.getFileName().toString());
        node.put("type", isFolder ? "folder" : "file");
        node.put("path", relative);
        return node;
    }

    /**
     * 判断路径是否为隐藏文件（以 . 开头）。
     */
    private boolean isHidden(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith(".");
    }
}