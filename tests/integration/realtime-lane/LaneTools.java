import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.flink.CatalogLoader;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.types.Types;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * realtime lane read tool (2026-11-15 lane batch, E2E acceptance infrastructure).
 *
 * usage: LaneTools <create|count|dump> <warehouseUri> <db> <table>
 *   create : build target table (id long, name string, amount double, src_ts timestamptz)
 *            and set identifier fields (upsert precondition)
 *   count  : print ROWS=<n>
 *   dump   : print CSV lines id,srcMs,readMs (src_ts -> epoch millis, readMs = now)
 *
 * compile (outside maven, needs module dependency classpath + iceberg-api jar):
 *   javac -d <out> -cp "$LANE_CP" LaneTools.java
 */
public final class LaneTools {

    private LaneTools() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        String warehouse = args[1];
        String db = args[2];
        String tbl = args[3];

        Map<String, String> props = new HashMap<>();
        props.put("warehouse", warehouse);
        Catalog cat = CatalogLoader.hadoop("lane", new org.apache.hadoop.conf.Configuration(), props)
                .loadCatalog();
        TableIdentifier ident = TableIdentifier.of(db, tbl);

        if ("create".equals(mode)) {
            Schema schema = new Schema(
                    Types.NestedField.required(1, "id", Types.LongType.get()),
                    Types.NestedField.optional(2, "name", Types.StringType.get()),
                    Types.NestedField.optional(3, "amount", Types.DoubleType.get()),
                    Types.NestedField.optional(4, "src_ts", Types.TimestampType.withZone()));
            if (!cat.tableExists(ident)) {
                cat.createTable(ident, schema);
            }
            cat.loadTable(ident).updateSchema().setIdentifierFields("id").commit();
            System.out.println("TABLE-READY");
        } else if ("count".equals(mode)) {
            cat.loadTable(ident).refresh();
            long c = 0;
            try (CloseableIterable<Record> it = IcebergGenerics.read(cat.loadTable(ident)).build()) {
                for (Record ignored : it) {
                    c++;
                }
            }
            System.out.println("ROWS=" + c);
        } else if ("dump".equals(mode)) {
            cat.loadTable(ident).refresh();
            long readMs = System.currentTimeMillis();
            try (CloseableIterable<Record> it = IcebergGenerics.read(cat.loadTable(ident)).build()) {
                for (Record r : it) {
                    Object ts = r.getField("src_ts");
                    long srcMs = -1;
                    if (ts instanceof OffsetDateTime o) {
                        srcMs = o.toInstant().toEpochMilli();
                    } else if (ts instanceof ZonedDateTime z) {
                        srcMs = z.toInstant().toEpochMilli();
                    } else if (ts instanceof LocalDateTime l) {
                        srcMs = l.toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
                    }
                    System.out.println(r.getField("id") + "," + srcMs + "," + readMs);
                }
            }
        } else {
            throw new IllegalArgumentException("mode must be create|count|dump: " + mode);
        }
    }
}
