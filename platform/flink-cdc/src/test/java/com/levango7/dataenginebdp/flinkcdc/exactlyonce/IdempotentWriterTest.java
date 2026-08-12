package com.levango7.dataenginebdp.flinkcdc.exactlyonce;

import com.levango7.dataenginebdp.flinkcdc.model.ChangeRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link IdempotentWriter} 单元测试。
 *
 * @author shuqing-bigdata
 */
class IdempotentWriterTest {

    private ChangeRecord record(String op, Map<String, Object> after, Map<String, Object> source) {
        return new ChangeRecord(null, after, op, source, 100L);
    }

    private Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Nested
    @DisplayName("主键去重策略")
    class PrimaryKeyStrategyTest {

        @Test
        @DisplayName("相同主键 — 后者覆盖前者")
        void samePrimaryKey_overwrite() {
            IdempotentWriter writer = IdempotentWriter.builder()
                    .strategy(ExactlyOnceConfig.IdempotentStrategy.PRIMARY_KEY)
                    .primaryKeyColumns("id")
                    .build();

            ChangeRecord r1 = record("c", map("id", 1, "name", "alice"), null);
            ChangeRecord r2 = record("u", map("id", 1, "name", "bob"), null);

            assertThat(writer.write(r1)).isTrue();
            assertThat(writer.write(r2)).isTrue();  // 覆盖，但仍被接受

            List<ChangeRecord> pending = writer.drainPending();
            assertThat(pending).hasSize(1);
            assertThat(pending.get(0).getAfter().get("name")).isEqualTo("bob");
        }

        @Test
        @DisplayName("不同主键 — 全部保留")
        void differentPrimaryKey_keepAll() {
            IdempotentWriter writer = IdempotentWriter.builder()
                    .primaryKeyColumns("id")
                    .build();

            ChangeRecord r1 = record("c", map("id", 1, "name", "alice"), null);
            ChangeRecord r2 = record("c", map("id", 2, "name", "bob"), null);

            writer.write(r1);
            writer.write(r2);

            assertThat(writer.drainPending()).hasSize(2);
        }

        @Test
        @DisplayName("复合主键 — 正确去重")
        void compositePrimaryKey() {
            IdempotentWriter writer = IdempotentWriter.builder()
                    .primaryKeyColumns("tenant_id", "order_id")
                    .build();

            ChangeRecord r1 = record("c", map("tenant_id", "t1", "order_id", 100, "amount", 50.0), null);
            ChangeRecord r2 = record("u", map("tenant_id", "t1", "order_id", 100, "amount", 60.0), null);
            ChangeRecord r3 = record("c", map("tenant_id", "t2", "order_id", 100, "amount", 70.0), null);

            writer.write(r1);
            writer.write(r2);
            writer.write(r3);

            List<ChangeRecord> pending = writer.drainPending();
            assertThat(pending).hasSize(2);
        }

        @Test
        @DisplayName("DELETE 操作 — 使用 before 作为主键来源")
        void deleteUsesBefore() {
            IdempotentWriter writer = IdempotentWriter.builder()
                    .primaryKeyColumns("id")
                    .build();

            ChangeRecord r1 = new ChangeRecord(map("id", 1, "name", "alice"), null, "d", null, 100L);
            ChangeRecord r2 = new ChangeRecord(map("id", 1, "name", "alice"), null, "d", null, 100L);

            writer.write(r1);
            assertThat(writer.write(r2)).isTrue();  // 相同主键覆盖，但仍被接受
        }

        @Test
        @DisplayName("统计计数 — 正确")
        void statistics() {
            IdempotentWriter writer = IdempotentWriter.builder()
                    .primaryKeyColumns("id")
                    .build();

            writer.write(record("c", map("id", 1), null));
            writer.write(record("c", map("id", 1), null));  // 覆盖
            writer.write(record("c", map("id", 2), null));

            assertThat(writer.getTotalAttempted()).isEqualTo(3);
            assertThat(writer.getTotalDeduplicated()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("版本号去重策略")
    class VersionStrategyTest {

        @Test
        @DisplayName("更高版本 — 接受")
        void higherVersion_accepted() {
            IdempotentWriter writer = IdempotentWriter.builder()
                    .strategy(ExactlyOnceConfig.IdempotentStrategy.VERSION)
                    .primaryKeyColumns("id")
                    .versionColumn("version")
                    .build();

            assertThat(writer.write(record("c", map("id", 1, "version", 1L), null))).isTrue();
            assertThat(writer.write(record("u", map("id", 1, "version", 2L), null))).isTrue();
            assertThat(writer.write(record("u", map("id", 1, "version", 3L), null))).isTrue();

            List<ChangeRecord> pending = writer.drainPending();
            assertThat(pending).hasSize(1);
            assertThat(pending.get(0).getAfter().get("version")).isEqualTo(3L);
        }

        @Test
        @DisplayName("更低版本 — 丢弃")
        void lowerVersion_discarded() {
            IdempotentWriter writer = IdempotentWriter.builder()
                    .strategy(ExactlyOnceConfig.IdempotentStrategy.VERSION)
                    .primaryKeyColumns("id")
                    .versionColumn("version")
                    .build();

            writer.write(record("c", map("id", 1, "version", 5L), null));
            assertThat(writer.write(record("u", map("id", 1, "version", 3L), null))).isFalse();

            List<ChangeRecord> pending = writer.drainPending();
            assertThat(pending).hasSize(1);
            assertThat(pending.get(0).getAfter().get("version")).isEqualTo(5L);
        }

        @Test
        @DisplayName("相同版本 — 丢弃")
        void sameVersion_discarded() {
            IdempotentWriter writer = IdempotentWriter.builder()
                    .strategy(ExactlyOnceConfig.IdempotentStrategy.VERSION)
                    .primaryKeyColumns("id")
                    .versionColumn("version")
                    .build();

            writer.write(record("c", map("id", 1, "version", 5L), null));
            assertThat(writer.write(record("u", map("id", 1, "version", 5L), null))).isFalse();
        }

        @Test
        @DisplayName("markCommitted 后 — 旧版本被去重")
        void afterMarkCommitted() {
            IdempotentWriter writer = IdempotentWriter.builder()
                    .strategy(ExactlyOnceConfig.IdempotentStrategy.VERSION)
                    .primaryKeyColumns("id")
                    .versionColumn("version")
                    .build();

            writer.write(record("c", map("id", 1, "version", 5L), null));
            writer.markCommitted();

            // 重放旧版本应被丢弃
            assertThat(writer.write(record("c", map("id", 1, "version", 5L), null))).isFalse();
            assertThat(writer.write(record("c", map("id", 1, "version", 4L), null))).isFalse();
            assertThat(writer.write(record("c", map("id", 1, "version", 6L), null))).isTrue();
        }

        @Test
        @DisplayName("版本号为字符串数字 — 正确解析")
        void stringVersionNumber() {
            IdempotentWriter writer = IdempotentWriter.builder()
                    .strategy(ExactlyOnceConfig.IdempotentStrategy.VERSION)
                    .primaryKeyColumns("id")
                    .versionColumn("version")
                    .build();

            writer.write(record("c", map("id", 1, "version", "10"), null));
            assertThat(writer.write(record("u", map("id", 1, "version", "15"), null))).isTrue();
            assertThat(writer.write(record("u", map("id", 1, "version", "12"), null))).isFalse();
        }
    }

    @Nested
    @DisplayName("TXN-LSN 去重策略")
    class TxnLsnStrategyTest {

        @Test
        @DisplayName("更高 LSN — 接受")
        void higherLsn_accepted() {
            IdempotentWriter writer = IdempotentWriter.builder()
                    .strategy(ExactlyOnceConfig.IdempotentStrategy.TXN_LSN)
                    .build();

            Map<String, Object> src1 = map("file", "binlog.000001", "pos", 100L);
            Map<String, Object> src2 = map("file", "binlog.000001", "pos", 200L);

            assertThat(writer.write(record("c", map("id", 1), src1))).isTrue();
            assertThat(writer.write(record("u", map("id", 1), src2))).isTrue();

            List<ChangeRecord> pending = writer.drainPending();
            assertThat(pending).hasSize(1);
            assertThat(pending.get(0).getSource().get("pos")).isEqualTo(200L);
        }

        @Test
        @DisplayName("更低 LSN — 丢弃")
        void lowerLsn_discarded() {
            IdempotentWriter writer = IdempotentWriter.builder()
                    .strategy(ExactlyOnceConfig.IdempotentStrategy.TXN_LSN)
                    .build();

            Map<String, Object> src1 = map("file", "binlog.000001", "pos", 200L);
            Map<String, Object> src2 = map("file", "binlog.000001", "pos", 100L);

            writer.write(record("c", map("id", 1), src1));
            assertThat(writer.write(record("u", map("id", 1), src2))).isFalse();
        }

        @Test
        @DisplayName("source 为 null — 无去重键，按非幂等写入")
        void nullSource() {
            IdempotentWriter writer = IdempotentWriter.builder()
                    .strategy(ExactlyOnceConfig.IdempotentStrategy.TXN_LSN)
                    .build();

            ChangeRecord r = record("c", map("id", 1), null);
            assertThat(writer.write(r)).isTrue();
        }

        @Test
        @DisplayName("markCommitted 后 — 旧 LSN 被去重")
        void afterMarkCommitted() {
            IdempotentWriter writer = IdempotentWriter.builder()
                    .strategy(ExactlyOnceConfig.IdempotentStrategy.TXN_LSN)
                    .build();

            Map<String, Object> src1 = map("file", "binlog.000001", "pos", 200L);
            writer.write(record("c", map("id", 1), src1));
            writer.markCommitted();

            Map<String, Object> src2 = map("file", "binlog.000001", "pos", 150L);
            assertThat(writer.write(record("c", map("id", 1), src2))).isFalse();
        }
    }

    @Nested
    @DisplayName("批量写入与缓冲管理")
    class BatchAndBufferTest {

        @Test
        @DisplayName("writeAll — 批量写入返回接受数")
        void writeAll() {
            IdempotentWriter writer = IdempotentWriter.builder()
                    .primaryKeyColumns("id")
                    .build();

            List<ChangeRecord> records = List.of(
                    record("c", map("id", 1), null),
                    record("c", map("id", 1), null),  // 覆盖
                    record("c", map("id", 2), null));

            int accepted = writer.writeAll(records);
            // 主键策略下覆盖也返回 true（记录被接受，覆盖旧版本）
            assertThat(accepted).isEqualTo(3);
            // 但去重后缓冲中只有 2 条
            assertThat(writer.getPendingCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("drainPending — 清空缓冲")
        void drainPending_clearsBuffer() {
            IdempotentWriter writer = IdempotentWriter.builder()
                    .primaryKeyColumns("id")
                    .build();

            writer.write(record("c", map("id", 1), null));
            writer.drainPending();
            assertThat(writer.getPendingCount()).isZero();
            assertThat(writer.drainPending()).isEmpty();
        }

        @Test
        @DisplayName("snapshotPending — 不清空缓冲")
        void snapshotPending_keepsBuffer() {
            IdempotentWriter writer = IdempotentWriter.builder()
                    .primaryKeyColumns("id")
                    .build();

            writer.write(record("c", map("id", 1), null));
            List<ChangeRecord> snapshot = writer.snapshotPending();
            assertThat(snapshot).hasSize(1);
            assertThat(writer.getPendingCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("rollback — 清空缓冲")
        void rollback() {
            IdempotentWriter writer = IdempotentWriter.builder()
                    .primaryKeyColumns("id")
                    .build();

            writer.write(record("c", map("id", 1), null));
            writer.rollback();
            assertThat(writer.getPendingCount()).isZero();
        }

        @Test
        @DisplayName("null record — 抛出 NPE")
        void nullRecord() {
            IdempotentWriter writer = IdempotentWriter.builder().primaryKeyColumns("id").build();
            assertThatThrownBy(() -> writer.write(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null records 集合 — 抛出 NPE")
        void nullRecords() {
            IdempotentWriter writer = IdempotentWriter.builder().primaryKeyColumns("id").build();
            assertThatThrownBy(() -> writer.writeAll(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTest {

        @Test
        @DisplayName("fromConfig — 从 ExactlyOnceConfig 复制")
        void fromConfig() {
            ExactlyOnceConfig config = ExactlyOnceConfig.builder()
                    .transactionalIdPrefix("tx-")
                    .idempotentStrategy(ExactlyOnceConfig.IdempotentStrategy.VERSION)
                    .primaryKeyColumns("id,version")
                    .versionColumn("version")
                    .build();

            IdempotentWriter writer = IdempotentWriter.builder().fromConfig(config).build();

            assertThat(writer.getStrategy()).isEqualTo(ExactlyOnceConfig.IdempotentStrategy.VERSION);
            assertThat(writer.getPrimaryKeyColumns()).containsExactly("id", "version");
            assertThat(writer.getVersionColumn()).isEqualTo("version");
        }

        @Test
        @DisplayName("primaryKeyColumns 字符串 — 去空格去重")
        void primaryKeyColumnsString() {
            IdempotentWriter writer = IdempotentWriter.builder()
                    .primaryKeyColumns(" id , id , name ")
                    .build();

            assertThat(writer.getPrimaryKeyColumns()).containsExactly("id", "name");
        }

        @Test
        @DisplayName("null 参数 — 抛出 NPE")
        void nullParams() {
            assertThatThrownBy(() -> IdempotentWriter.builder().strategy(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> IdempotentWriter.builder().primaryKeyColumns((String) null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> IdempotentWriter.builder().fromConfig(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}