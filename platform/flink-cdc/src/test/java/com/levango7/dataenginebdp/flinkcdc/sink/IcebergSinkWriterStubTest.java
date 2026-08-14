package com.levango7.dataenginebdp.flinkcdc.sink;

import com.levango7.dataenginebdp.flinkcdc.model.ChangeRecord;
import org.apache.flink.api.connector.sink.SinkWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IcebergSinkWriterStub WAL 落盘测试（5.2 修复：write/flush/close 不再为空操作）。
 */
class IcebergSinkWriterStubTest {

    @TempDir
    Path tempDir;

    private IcebergSinkConnector.IcebergSinkWriterStub newStub(Map<String, String> props) {
        try {
            var ctor = IcebergSinkConnector.IcebergSinkWriterStub.class
                    .getDeclaredConstructor(Map.class);
            ctor.setAccessible(true);
            return ctor.newInstance(props);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ChangeRecord record(String table, String op, Object id) {
        ChangeRecord r = new ChangeRecord();
        r.setOp(op);
        r.setTsMs(System.currentTimeMillis());
        Map<String, Object> after = new HashMap<>();
        after.put("id", id);
        after.put("name", "row-" + id);
        r.setAfter(after);
        return r;
    }

    @Test
    void write_flush_close_persistsRecordsToWal() throws Exception {
        Map<String, String> props = new HashMap<>();
        props.put("wal.enabled", "true");
        props.put("wal.path", tempDir.toString());

        IcebergSinkConnector.IcebergSinkWriterStub stub = newStub(props);
        stub.write(record("ods.orders", "c", 1), null);
        stub.write(record("ods.orders", "c", 2), null);
        stub.write(record("ods.orders", "u", 3), null);
        stub.flush(false);
        stub.close();

        // WAL 文件应已生成且包含 3 行 JSON
        File[] wals = tempDir.toFile().listFiles((dir, name) -> name.endsWith(".wal"));
        assertThat(wals).isNotNull().hasSize(1);

        int lines = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(wals[0]))) {
            while (reader.readLine() != null) {
                lines++;
            }
        }
        assertThat(lines).isEqualTo(3);
    }

    @Test
    void write_counters_trackRecords() throws Exception {
        Map<String, String> props = new HashMap<>();
        props.put("wal.enabled", "false"); // 关闭落盘，仅计数
        IcebergSinkConnector.IcebergSinkWriterStub stub = newStub(props);

        stub.write(record("ods.orders", "c", 1), null);
        stub.write(record("ods.orders", "d", 2), null);

        assertThat(stub.getWrittenRecords()).isEqualTo(2);
        stub.flush(true);
        stub.close();
    }

    @Test
    void disabledWal_noFileCreated() throws Exception {
        Map<String, String> props = new HashMap<>();
        props.put("wal.enabled", "false");
        props.put("wal.path", tempDir.toString());

        IcebergSinkConnector.IcebergSinkWriterStub stub = newStub(props);
        stub.write(record("ods.orders", "c", 1), null);
        stub.flush(false);
        stub.close();

        File[] wals = tempDir.toFile().listFiles((dir, name) -> name.endsWith(".wal"));
        assertThat(wals).isNullOrEmpty();
    }
}
