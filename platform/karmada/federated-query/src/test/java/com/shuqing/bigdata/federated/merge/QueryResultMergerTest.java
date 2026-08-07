package com.shuqing.bigdata.federated.merge;

import com.shuqing.bigdata.federated.model.ClusterQueryResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QueryResultMerger} 单元测试。
 */
class QueryResultMergerTest {

    private final QueryResultMerger merger = new QueryResultMerger();

    private ClusterQueryResult result(String cluster, Map<String, String> schema, List<Map<String, Object>> rows) {
        return ClusterQueryResult.builder()
                .cluster(cluster)
                .success(true)
                .schema(schema)
                .rows(rows)
                .rowCount(rows.size())
                .build();
    }

    @Test
    void concat_shouldPreserveOrder() {
        Map<String, String> schema = Map.of("id", "INT", "name", "STRING");
        ClusterQueryResult r1 = result("c1", schema, List.of(
                Map.of("id", 1, "name", "a"),
                Map.of("id", 2, "name", "b")));
        ClusterQueryResult r2 = result("c2", schema, List.of(
                Map.of("id", 3, "name", "c")));

        QueryResultMerger.MergedResult merged = merger.merge(List.of(r1, r2), MergeStrategy.CONCAT);

        assertThat(merged.totalRows()).isEqualTo(3);
        assertThat(merged.rows()).extracting(r -> r.get("id")).containsExactly(1, 2, 3);
    }

    @Test
    void union_shouldDeduplicate() {
        Map<String, String> schema = Map.of("id", "INT");
        ClusterQueryResult r1 = result("c1", schema, List.of(
                Map.of("id", 1), Map.of("id", 2)));
        ClusterQueryResult r2 = result("c2", schema, List.of(
                Map.of("id", 2), Map.of("id", 3)));

        QueryResultMerger.MergedResult merged = merger.merge(List.of(r1, r2), MergeStrategy.UNION);

        assertThat(merged.totalRows()).isEqualTo(3);
    }

    @Test
    void aggregate_shouldSumNumericColumns() {
        Map<String, String> schema = Map.of("count", "INT", "region", "STRING");
        ClusterQueryResult r1 = result("c1", schema, List.of(Map.of("count", 10, "region", "east")));
        ClusterQueryResult r2 = result("c2", schema, List.of(Map.of("count", 20, "region", "west")));

        QueryResultMerger.MergedResult merged = merger.merge(List.of(r1, r2), MergeStrategy.AGGREGATE);

        assertThat(merged.totalRows()).isEqualTo(1);
        assertThat(merged.rows().get(0).get("count")).isEqualTo(30L);
    }

    @Test
    void emptyResults_shouldReturnEmpty() {
        QueryResultMerger.MergedResult merged = merger.merge(List.of(), MergeStrategy.CONCAT);
        assertThat(merged.totalRows()).isZero();
    }

    @Test
    void failedResults_shouldBeSkipped() {
        ClusterQueryResult failed = ClusterQueryResult.builder()
                .cluster("c1").success(false).error("timeout").build();
        Map<String, String> schema = Map.of("id", "INT");
        ClusterQueryResult ok = result("c2", schema, List.of(Map.of("id", 1)));

        QueryResultMerger.MergedResult merged = merger.merge(List.of(failed, ok), MergeStrategy.CONCAT);

        assertThat(merged.totalRows()).isEqualTo(1);
    }
}