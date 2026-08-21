package com.shuqing.bigdata.encaps.security.facade.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shuqing.bigdata.encaps.security.facade.config.SecurityFacadeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EvidenceArchive} 单元测试。
 *
 * <p>使用 {@code @TempDir} 提供临时归档目录，测试后自动清理。</p>
 */
class EvidenceArchiveTest {

    @TempDir
    Path tempDir;

    private EvidenceArchive archive;
    private SecurityFacadeConfig config;

    @BeforeEach
    void setUp() {
        config = new SecurityFacadeConfig();
        config.getEvidence().setArchiveDir(tempDir.toString());
        archive = new EvidenceArchive(config, new ObjectMapper());
    }

    @Test
    @DisplayName("archive — 写入文件并填充校验和")
    void archive_shouldWriteFileAndFillChecksum() throws IOException {
        EvidenceItem item = new EvidenceItem(
                "test-id-1", EvidenceType.AUDIT_LOG, Instant.now(),
                "test event", "AuditFacade",
                Map.of("action", "LOGIN"), null);

        EvidenceItem archived = archive.archive(item);

        assertThat(archived.getChecksum()).isNotBlank();
        // 文件应存在
        Path dayDir = tempDir.resolve(archived.getTimestamp()
                .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString());
        Path file = dayDir.resolve("test-id-1.json");
        assertThat(file).exists();
    }

    @Test
    @DisplayName("archiveAll — 批量归档")
    void archiveAll_shouldArchiveAll() throws IOException {
        EvidenceItem item1 = new EvidenceItem(
                "id-1", EvidenceType.AUDIT_LOG, Instant.now(),
                "event 1", "src", Map.of(), null);
        EvidenceItem item2 = new EvidenceItem(
                "id-2", EvidenceType.CONFIG_SNAPSHOT, Instant.now(),
                "snapshot", "src", Map.of(), null);

        List<EvidenceItem> archived = archive.archiveAll(List.of(item1, item2));

        assertThat(archived).hasSize(2);
        assertThat(archived).allSatisfy(item -> assertThat(item.getChecksum()).isNotBlank());
    }

    @Test
    @DisplayName("verify — 归档后校验通过")
    void verify_afterArchive_shouldPass() throws IOException {
        EvidenceItem item = new EvidenceItem(
                "verify-id", EvidenceType.AUDIT_LOG, Instant.now(),
                "test", "src", Map.of("key", "value"), null);

        EvidenceItem archived = archive.archive(item);
        Path dayDir = tempDir.resolve(archived.getTimestamp()
                .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString());
        Path file = dayDir.resolve("verify-id.json");

        boolean valid = archive.verify(file);
        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("verify — 篡改文件后校验失败")
    void verify_tamperedFile_shouldFail() throws IOException {
        EvidenceItem item = new EvidenceItem(
                "tamper-id", EvidenceType.AUDIT_LOG, Instant.now(),
                "test", "src", Map.of("key", "value"), null);

        EvidenceItem archived = archive.archive(item);
        Path dayDir = tempDir.resolve(archived.getTimestamp()
                .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString());
        Path file = dayDir.resolve("tamper-id.json");

        // 篡改文件内容
        String content = Files.readString(file);
        String tampered = content.replace("test", "TAMPERED");
        Files.writeString(file, tampered);

        boolean valid = archive.verify(file);
        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("listByDay — 列出指定日期的证据文件")
    void listByDay_shouldListFiles() throws IOException {
        EvidenceItem item = new EvidenceItem(
                "list-id", EvidenceType.AUDIT_LOG, Instant.now(),
                "test", "src", Map.of(), null);

        archive.archive(item);

        String day = item.getTimestamp()
                .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
        List<Path> files = archive.listByDay(day);

        assertThat(files).hasSize(1);
        assertThat(files.get(0).getFileName().toString()).endsWith(".json");
    }

    @Test
    @DisplayName("listByDay — 不存在的日期返回空列表")
    void listByDay_nonExistentDay_shouldReturnEmpty() throws IOException {
        List<Path> files = archive.listByDay("2099-01-01");
        assertThat(files).isEmpty();
    }
}