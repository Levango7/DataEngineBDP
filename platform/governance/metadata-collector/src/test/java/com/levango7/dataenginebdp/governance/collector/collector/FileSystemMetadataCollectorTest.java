package com.levango7.dataenginebdp.governance.collector.collector;

import com.levango7.dataenginebdp.governance.collector.model.CollectionResult;
import com.levango7.dataenginebdp.governance.collector.model.MetadataSource;
import com.levango7.dataenginebdp.governance.collector.model.TableMetadata;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link FileSystemMetadataCollector} 单元测试。
 *
 * <p>使用 Mockito mock {@link FileSystem}，避免真实 HDFS 连接。
 * 通过子类化覆盖 {@link FileSystemMetadataCollector#createFileSystem} 注入 mock。</p>
 */
class FileSystemMetadataCollectorTest {

    private TestableFileSystemMetadataCollector collector;

    @BeforeEach
    void setUp() {
        collector = new TestableFileSystemMetadataCollector(3);
    }

    @Test
    @DisplayName("getType 应返回 FILESYSTEM")
    void getType_shouldReturnFilesystem() {
        assertEquals(MetadataSource.TYPE_FILESYSTEM, collector.getType());
    }

    @Test
    @DisplayName("collect 成功路径：应递归采集目录与文件元数据")
    void collect_successPath() throws Exception {
        MetadataSource source = new MetadataSource();
        source.setId(1L);
        source.setName("hdfs-test");
        source.setType(MetadataSource.TYPE_FILESYSTEM);
        source.setUrl("hdfs://localhost:8020/data");

        FileSystem mockFs = mock(FileSystem.class);

        // /data 目录
        Path rootPath = new Path("/data");
        when(mockFs.listStatus(rootPath)).thenReturn(new FileStatus[]{
                makeFileStatus("/data/users.parquet", 1024L, false),
                makeFileStatus("/data/subdir", 0L, true)
        });

        // /data/subdir 目录
        Path subdirPath = new Path("/data/subdir");
        when(mockFs.listStatus(subdirPath)).thenReturn(new FileStatus[]{
                makeFileStatus("/data/subdir/orders.orc", 2048L, false),
                makeFileStatus("/data/subdir/notes.csv", 100L, false)
        });

        // 深度 2 的子目录（应被 maxDepth=3 允许）
        Path deepDir = new Path("/data/subdir/deep");
        when(mockFs.listStatus(deepDir)).thenReturn(new FileStatus[]{
                makeFileStatus("/data/subdir/deep/file.txt", 50L, false)
        });

        // 修正 subdir 的 listStatus 包含 deep 目录
        when(mockFs.listStatus(subdirPath)).thenReturn(new FileStatus[]{
                makeFileStatus("/data/subdir/orders.orc", 2048L, false),
                makeFileStatus("/data/subdir/notes.csv", 100L, false),
                makeFileStatus("/data/subdir/deep", 0L, true)
        });

        collector.setMockFs(mockFs);

        CollectionResult result = collector.collect(source);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getDatabaseCount());
        assertNotNull(result.getTables());
        assertFalse(result.getTables().isEmpty());

        // 验证根目录表元数据
        TableMetadata root = result.getTables().stream()
                .filter(t -> "/data".equals(t.getTableName()))
                .findFirst()
                .orElseThrow();
        assertEquals("filesystem", root.getDatabaseName());
        assertEquals("DIRECTORY", root.getTableType());
        assertNotNull(root.getProperties());
        assertEquals("1", root.getProperties().get("fileCount")); // users.parquet
        assertEquals("1", root.getProperties().get("dirCount"));  // subdir
        assertEquals("1024", root.getProperties().get("totalSize"));
        assertTrue(root.getProperties().get("formats").contains("PARQUET"));

        // 验证子目录表元数据
        TableMetadata subdir = result.getTables().stream()
                .filter(t -> "/data/subdir".equals(t.getTableName()))
                .findFirst()
                .orElseThrow();
        assertEquals("2", subdir.getProperties().get("fileCount"));
        assertEquals("2148", subdir.getProperties().get("totalSize"));
        assertTrue(subdir.getProperties().get("formats").contains("ORC"));
        assertTrue(subdir.getProperties().get("formats").contains("CSV"));
    }

    @Test
    @DisplayName("collect 失败路径：FileSystem 创建失败应返回 success=false")
    void collect_failurePath() throws Exception {
        MetadataSource source = new MetadataSource();
        source.setId(2L);
        source.setName("hdfs-down");
        source.setType(MetadataSource.TYPE_FILESYSTEM);
        source.setUrl("hdfs://unreachable:8020/data");

        // 配置 createFileSystem 抛出 IOException
        collector.setCreateFailure(new IOException("connection refused"));

        CollectionResult result = collector.collect(source);
        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("connection refused"));
    }

    @Test
    @DisplayName("testConnection 路径存在应返回 true")
    void testConnection_exists() throws Exception {
        MetadataSource source = new MetadataSource();
        source.setId(1L);
        source.setName("hdfs-ok");
        source.setType(MetadataSource.TYPE_FILESYSTEM);
        source.setUrl("hdfs://localhost:8020/data");

        FileSystem mockFs = mock(FileSystem.class);
        when(mockFs.exists(any(Path.class))).thenReturn(true);

        collector.setMockFs(mockFs);

        assertTrue(collector.testConnection(source));
    }

    @Test
    @DisplayName("testConnection 路径不存在应返回 false")
    void testConnection_notExists() throws Exception {
        MetadataSource source = new MetadataSource();
        source.setId(1L);
        source.setName("hdfs-missing");
        source.setType(MetadataSource.TYPE_FILESYSTEM);
        source.setUrl("hdfs://localhost:8020/nonexistent");

        FileSystem mockFs = mock(FileSystem.class);
        when(mockFs.exists(any(Path.class))).thenReturn(false);

        collector.setMockFs(mockFs);

        assertFalse(collector.testConnection(source));
    }

    /**
     * 构造一个 FileStatus。
     *
     * @param path     路径
     * @param length   文件大小
     * @param isDir    是否目录
     * @return FileStatus
     */
    private FileStatus makeFileStatus(String path, long length, boolean isDir) {
        // FileStatus 构造函数：length, isDir, replication, blockSize, modificationTime, path
        return new FileStatus(
                length,
                isDir,
                1,
                128 * 1024 * 1024L,
                System.currentTimeMillis(),
                new Path(path)
        );
    }

    /**
     * 可测试子类：覆盖 createFileSystem 返回 mock 或抛出预设异常。
     */
    static class TestableFileSystemMetadataCollector extends FileSystemMetadataCollector {
        private FileSystem mockFs;
        private Exception createFailure;

        TestableFileSystemMetadataCollector(int maxDepth) {
            super(maxDepth);
        }

        void setMockFs(FileSystem fs) {
            this.mockFs = fs;
        }

        void setCreateFailure(Exception ex) {
            this.createFailure = ex;
        }

        @Override
        protected FileSystem createFileSystem(MetadataSource source) throws IOException, java.net.URISyntaxException {
            if (createFailure != null) {
                if (createFailure instanceof IOException) {
                    throw (IOException) createFailure;
                }
                if (createFailure instanceof java.net.URISyntaxException) {
                    throw (java.net.URISyntaxException) createFailure;
                }
                throw new IOException(createFailure);
            }
            return mockFs;
        }
    }
}