package com.levango7.dataenginebdp.streambatch.job;

import com.levango7.dataenginebdp.streambatch.model.DagExecutionResult;
import com.levango7.dataenginebdp.streambatch.model.ExecutionStatus;
import com.levango7.dataenginebdp.streambatch.model.StreamBatchDag;
import com.levango7.dataenginebdp.streambatch.service.StreamBatchOrchestrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JobService 单元测试（CRUD + 作业→DAG 映射）。
 */
@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private StreamBatchOrchestrationService orchestrationService;

    private JobService newService() {
        return new JobService(jobRepository, orchestrationService);
    }

    private JobEntity sampleJob(Long id) {
        return JobEntity.builder()
                .id(id)
                .name("etl-job")
                .workspaceId("ws-1")
                .type("spark")
                .config("{\"spark.sql\":\"SELECT 1\"}")
                .status("draft")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void create_setsDraftAndTimestamps() {
        JobEntity job = sampleJob(null);
        when(jobRepository.save(any())).thenAnswer(inv -> {
            JobEntity j = inv.getArgument(0);
            j.setId(1L);
            return j;
        });

        JobEntity saved = newService().create(job);
        assertThat(saved.getId()).isEqualTo(1L);
        assertThat(saved.getStatus()).isEqualTo("draft");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void run_submitsSingleNodeDag() {
        JobEntity job = sampleJob(5L);
        when(jobRepository.findById(5L)).thenReturn(java.util.Optional.of(job));
        DagExecutionResult result = new DagExecutionResult();
        result.setDagId("job-5");
        result.setStatus(ExecutionStatus.RUNNING);
        when(orchestrationService.submitDag(any())).thenReturn(result);

        var got = newService().run(5L);
        assertThat(got).isPresent();
        assertThat(got.get().getDagId()).isEqualTo("job-5");

        // 验证提交的是单节点 DAG，类型映射 spark → SPARK_BATCH
        ArgumentCaptor<StreamBatchDag> captor = ArgumentCaptor.forClass(StreamBatchDag.class);
        verify(orchestrationService).submitDag(captor.capture());
        StreamBatchDag dag = captor.getValue();
        assertThat(dag.getDagId()).isEqualTo("job-5");
        assertThat(dag.getNodes()).hasSize(1);
        assertThat(dag.getNodes().get(0).getTaskType().getCode()).isEqualTo("SPARK_BATCH");
    }

    @Test
    void run_unknownTypeFallsBackToUnified() {
        JobEntity job = sampleJob(6L);
        job.setType("custom-unknown");
        when(jobRepository.findById(6L)).thenReturn(java.util.Optional.of(job));
        when(orchestrationService.submitDag(any())).thenReturn(new DagExecutionResult());

        newService().run(6L);
        ArgumentCaptor<StreamBatchDag> captor = ArgumentCaptor.forClass(StreamBatchDag.class);
        verify(orchestrationService).submitDag(captor.capture());
        assertThat(captor.getValue().getNodes().get(0).getTaskType().getCode())
                .isEqualTo("UNIFIED_STREAM_BATCH");
    }

    @Test
    void cancel_marksPaused() {
        JobEntity job = sampleJob(7L);
        when(jobRepository.findById(7L)).thenReturn(java.util.Optional.of(job));
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean ok = newService().cancel(7L);
        assertThat(ok).isTrue();
        assertThat(job.getStatus()).isEqualTo("paused");
        assertThat(job.getLastRunStatus()).isEqualTo("CANCELLED");
    }
}
