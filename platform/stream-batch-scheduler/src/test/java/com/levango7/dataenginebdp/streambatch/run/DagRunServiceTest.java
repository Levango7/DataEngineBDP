package com.levango7.dataenginebdp.streambatch.run;

import com.levango7.dataenginebdp.streambatch.dag.StreamBatchDagOrchestrator;
import com.levango7.dataenginebdp.streambatch.model.DagNode;
import com.levango7.dataenginebdp.streambatch.model.DagExecutionResult;
import com.levango7.dataenginebdp.streambatch.model.ExecutionStatus;
import com.levango7.dataenginebdp.streambatch.model.StreamBatchDag;
import com.levango7.dataenginebdp.streambatch.model.TaskExecutionResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * DagRunService 单元测试（不依赖真实 Spark/Flink，mock 编排器）。
 */
@DataJpaTest
@Import(DagRunService.class)
class DagRunServiceTest {

    @MockitoBean
    private StreamBatchDagOrchestrator orchestrator;

    @Autowired
    private DagRunService dagRunService;

    @Autowired
    private DagRunRepository dagRunRepository;

    /** 被测对象（直接用容器注入的 dagRunService）。 */
    private DagRunService service() {
        return dagRunService;
    }

    private StreamBatchDag sampleDag() {
        return StreamBatchDag.builder()
                .dagId("dag-test-001")
                .name("测试DAG")
                .nodes(List.of(DagNode.builder()
                        .nodeId("node-1")
                        .taskType(com.levango7.dataenginebdp.streambatch.model.TaskType.SPARK_BATCH)
                        .build()))
                .build();
    }

    private DagExecutionResult successResult(String dagId) {
        return DagExecutionResult.builder()
                .dagId(dagId)
                .status(ExecutionStatus.SUCCESS)
                .startTime(Instant.now())
                .endTime(Instant.now())
                .totalDurationMs(1000)
                .nodeResults(List.of(TaskExecutionResult.builder()
                        .nodeId("node-1")
                        .status(ExecutionStatus.SUCCESS)
                        .build()))
                .build();
    }

    private DagExecutionResult failedResult(String dagId) {
        return DagExecutionResult.builder()
                .dagId(dagId)
                .status(ExecutionStatus.FAILED)
                .startTime(Instant.now())
                .endTime(Instant.now())
                .totalDurationMs(500)
                .nodeResults(List.of(TaskExecutionResult.builder()
                        .nodeId("node-1")
                        .status(ExecutionStatus.FAILED)
                        .errorMessage("spark-submit 失败")
                        .build()))
                .build();
    }

    @Test
    void recordRun_persistsRunEntity() {
        DagRunEntity saved = service().recordRun(
                sampleDag(), successResult("dag-test-001"),
                DagRunType.MANUAL, "tester", null, null);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getDagId()).isEqualTo("dag-test-001");
        assertThat(saved.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(saved.getRunType()).isEqualTo(DagRunType.MANUAL);
        assertThat(dagRunRepository.countByDagId("dag-test-001")).isEqualTo(1);
    }

    @Test
    void recordRun_storesErrorMessageOnFailure() {
        DagRunEntity saved = service().recordRun(
                sampleDag(), failedResult("dag-test-001"),
                DagRunType.MANUAL, "tester", null, null);

        assertThat(saved.getErrorMessage()).contains("node-1").contains("spark-submit");
    }

    @Test
    void listRuns_returnsPagedResults() {
        for (int i = 0; i < 5; i++) {
            service().recordRun(sampleDag(), successResult("dag-test-001"),
                    DagRunType.MANUAL, "tester", null, null);
        }

        var page = service().listRuns("dag-test-001", ExecutionStatus.SUCCESS, 0, 3);

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent().get(0).getStartTime())
                .isAfterOrEqualTo(page.getContent().get(2).getStartTime()); // 按时间倒序
    }

    @Test
    void rerun_replaysOriginalDagParameters() {
        when(orchestrator.orchestrate(any(StreamBatchDag.class)))
                .thenReturn(successResult("dag-test-001"));

        DagExecutionResult result = service().rerun("dag-test-001",
                service().recordRun(sampleDag(), successResult("dag-test-001"),
                        DagRunType.MANUAL, "tester", null, null).getId(),
                "operator-a");

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        // rerun 记录应已落库
        assertThat(dagRunRepository.findAll().stream()
                .anyMatch(r -> r.getRunType() == DagRunType.RERUN)).isTrue();
    }

    @Test
    void backfill_generatesInstancesForEachDay() {
        // 先造一条历史，作为 backfill 的 DAG 快照来源
        service().recordRun(sampleDag(), successResult("dag-test-001"),
                DagRunType.MANUAL, "tester", null, null);
        when(orchestrator.orchestrate(any(StreamBatchDag.class)))
                .thenAnswer(inv -> successResult("dag-test-001"));

        int created = service().backfill("dag-test-001",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), 1, "operator-b");

        assertThat(created).isEqualTo(7);
        assertThat(dagRunRepository.findByDagIdOrderByStartTimeDesc(
                "dag-test-001", org.springframework.data.domain.PageRequest.of(0, 10))
                .stream().filter(r -> r.getRunType() == DagRunType.BACKFILL).count()).isEqualTo(7);
    }

    @Test
    void backfill_rejectsInvalidRange() {
        service().recordRun(sampleDag(), successResult("dag-test-001"),
                DagRunType.MANUAL, "tester", null, null);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                service().backfill("dag-test-001",
                        LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 1), 1, "operator-b"));
    }
}
