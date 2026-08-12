package com.levango7.dataenginebdp.governance.collector.collector;

import com.levango7.dataenginebdp.governance.collector.model.CollectionResult;
import com.levango7.dataenginebdp.governance.collector.model.ColumnMetadata;
import com.levango7.dataenginebdp.governance.collector.model.MetadataSource;
import com.levango7.dataenginebdp.governance.collector.model.TableMetadata;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件系统元数据采集器。
 *
 * <p>通过 Hadoop {@link FileSystem} 采集 HDFS/对象存储元数据：
 * <ol>
 *   <li>目录结构（递归遍历，受 {@link #maxDepth} 限制）</li>
 *   <li>文件列表</li>
 *   <li>文件大小</li>
 *   <li>修改时间</li>
 *   <li>格式推断（按扩展名：.parquet/.orc/.csv/.json/.avro/.txt）</li>
 * </ol></p>
 *
 * <p>每个目录映射为一张 {@link TableMetadata}：
 * <ul>
 *   <li>databaseName = "filesystem"</li>
 *   <li>tableName = 目录路径</li>
 *   <li>columns = [path, size, modificationTime, owner, format, isDirectory]</li>
 *   <li>properties 包含 fileCount/totalSize/depth</li>
 * </ul></p>
 *
 * <p>HDFS URL 格式：{@code hdfs://namenode:8020/}，
 * 由 {@code source.url} 提供。</p>
 */
@Component
public class FileSystemMetadataCollector implements MetadataCollector {

    private static final Logger log = LoggerFactory.getLogger(FileSystemMetadataCollector.class);

    /** 默认 HDFS NameNode 端口 */
    private static final int DEFAULT_PORT = 8020;

    /** 默认根路径 */
    private static final String DEFAULT_PATH = "/";

    /** 已知文件格式扩展名 → 格式名映射 */
    private static final Map<String, String> FORMAT_BY_EXT = new HashMap<>();

    static {
        FORMAT_BY_EXT.put(".parquet", "PARQUET");
        FORMAT_BY_EXT.put(".orc", "ORC");
        FORMAT_BY_EXT.put(".csv", "CSV");
        FORMAT_BY_EXT.put(".tsv", "TSV");
        FORMAT_BY_EXT.put(".json", "JSON");
        FORMAT_BY_EXT.put(".avro", "AVRO");
        FORMAT_BY_EXT.put(".txt", "TEXT");
        FORMAT_BY_EXT.put(".log", "TEXT");
        FORMAT_BY_EXT.put(".xml", "XML");
        FORMAT_BY_EXT.put(".yaml", "YAML");
        FORMAT_BY_EXT.put(".yml", "YAML");
    }

    /** 递归遍历最大深度，避免过深目录树导致 OOM */
    private final int maxDepth;

    /**
     * 构造文件系统采集器。
     *
     * @param maxDepth 最大递归深度，从配置 {@code app.collector.filesystem.max-depth} 读取
     */
    public FileSystemMetadataCollector(
            @Value("${app.collector.filesystem.max-depth:5}") int maxDepth) {
        this.maxDepth = maxDepth;
    }

    @Override
    public String getType() {
        return MetadataSource.TYPE_FILESYSTEM;
    }

    @Override
    public CollectionResult collect(MetadataSource source) {
        CollectionResult result = CollectionResult.success(source.getId(), source.getName(), getType());
        FileSystem fs = null;
        try {
            fs = createFileSystem(source);
            Path rootPath = resolveRootPath(source);

            List<TableMetadata> tables = new ArrayList<>();
            collectDirectory(fs, rootPath, 0, tables);
            result.setTables(tables);
            result.setDatabaseCount(1); // 视为单一 "filesystem" 数据库
            result.markFinished();
            return result;
        } catch (IOException | URISyntaxException e) {
            log.error("Filesystem collection failed for source {}: {}", source.getName(), e.getMessage(), e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.markFinished();
            return result;
        } finally {
            closeQuietly(fs);
        }
    }

    @Override
    public boolean testConnection(MetadataSource source) {
        FileSystem fs = null;
        try {
            fs = createFileSystem(source);
            Path root = resolveRootPath(source);
            // 列出根目录验证连接
            return fs.exists(root);
        } catch (Exception e) {
            log.warn("Filesystem connection test failed for source {}: {}", source.getName(), e.getMessage());
            return false;
        } finally {
            closeQuietly(fs);
        }
    }

    /**
     * 创建 Hadoop FileSystem。
     *
     * @param source 数据源
     * @return FileSystem
     * @throws IOException        IO 异常
     * @throws URISyntaxException URL 解析异常
     */
    protected FileSystem createFileSystem(MetadataSource source) throws IOException, URISyntaxException {
        Configuration conf = new Configuration();
        // 默认 fs.defaultFS 由 URL 推断
        String url = source.getUrl();
        if (url == null || url.isEmpty()) {
            url = "hdfs://localhost:" + DEFAULT_PORT;
        }
        // 允许通过 connectionProps 注入额外配置（简化处理：仅设置 defaultFS）
        URI uri = new URI(url);
        return FileSystem.get(uri, conf);
    }

    /**
     * 解析采集根路径。
     *
     * @param source 数据源
     * @return Hadoop Path
     */
    private Path resolveRootPath(MetadataSource source) {
        String url = source.getUrl();
        if (url == null || url.isEmpty()) {
            return new Path(DEFAULT_PATH);
        }
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            return new Path(path == null || path.isEmpty() ? DEFAULT_PATH : path);
        } catch (URISyntaxException e) {
            return new Path(DEFAULT_PATH);
        }
    }

    /**
     * 递归采集目录。
     *
     * @param fs       FileSystem
     * @param path     当前路径
     * @param depth    当前深度
     * @param tables   采集结果累加列表
     * @throws IOException IO 异常
     */
    private void collectDirectory(FileSystem fs, Path path, int depth, List<TableMetadata> tables)
            throws IOException {
        if (depth > maxDepth) {
            log.debug("Skip path {} due to max depth {} exceeded", path, maxDepth);
            return;
        }

        FileStatus[] statuses;
        try {
            statuses = fs.listStatus(path);
        } catch (IOException e) {
            log.warn("Failed to list path {}: {}", path, e.getMessage());
            return;
        }

        // 将当前目录作为一张"表"
        tables.add(toTableMetadata(path, statuses, depth));

        // 递归子目录
        for (FileStatus status : statuses) {
            if (status.isDirectory()) {
                collectDirectory(fs, status.getPath(), depth + 1, tables);
            }
        }
    }

    /**
     * 将目录转换为表元数据。
     *
     * @param dirPath   目录路径
     * @param statuses  目录下文件列表
     * @param depth     当前深度
     * @return 表元数据
     */
    private TableMetadata toTableMetadata(Path dirPath, FileStatus[] statuses, int depth) {
        TableMetadata tm = new TableMetadata();
        tm.setDatabaseName("filesystem");
        tm.setTableName(dirPath.toString());
        tm.setTableType("DIRECTORY");
        tm.setSourceType(getType());

        // 列：path / size / modificationTime / owner / format / isDirectory
        List<ColumnMetadata> columns = new ArrayList<>();
        columns.add(new ColumnMetadata("path", "STRING", "File path", false, false, 1));
        columns.add(new ColumnMetadata("size", "BIGINT", "File size in bytes", false, false, 2));
        columns.add(new ColumnMetadata("modificationTime", "BIGINT", "Last modification timestamp", false, false, 3));
        columns.add(new ColumnMetadata("owner", "STRING", "File owner", false, false, 4));
        columns.add(new ColumnMetadata("format", "STRING", "Inferred file format", true, false, 5));
        columns.add(new ColumnMetadata("isDirectory", "BOOLEAN", "Whether path is a directory", false, false, 6));
        tm.setColumns(columns);

        // 属性：文件数/总大小/深度
        long totalSize = 0;
        int fileCount = 0;
        int dirCount = 0;
        List<String> formats = new ArrayList<>();
        for (FileStatus s : statuses) {
            if (s.isDirectory()) {
                dirCount++;
            } else {
                fileCount++;
                totalSize += s.getLen();
                String fmt = inferFormat(s.getPath().getName());
                if (fmt != null && !formats.contains(fmt)) {
                    formats.add(fmt);
                }
            }
        }

        Map<String, String> props = new HashMap<>();
        props.put("fileCount", String.valueOf(fileCount));
        props.put("dirCount", String.valueOf(dirCount));
        props.put("totalSize", String.valueOf(totalSize));
        props.put("depth", String.valueOf(depth));
        props.put("formats", String.join(",", formats));
        tm.setProperties(props);

        tm.setFileCount(fileCount);
        tm.setTotalSize(totalSize);
        tm.setRowCount((long) (fileCount + dirCount));
        return tm;
    }

    /**
     * 根据文件名扩展名推断格式。
     *
     * @param fileName 文件名
     * @return 格式名（大写）；无法推断返回 null
     */
    private String inferFormat(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == fileName.length() - 1) {
            return null;
        }
        String ext = fileName.substring(dotIdx).toLowerCase();
        return FORMAT_BY_EXT.get(ext);
    }

    /**
     * 安静关闭 FileSystem。
     *
     * @param fs FileSystem，可为 null
     */
    private void closeQuietly(FileSystem fs) {
        if (fs != null) {
            try {
                fs.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
    }
}