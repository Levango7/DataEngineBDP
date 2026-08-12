package com.levango7.dataenginebdp.flinkcdc;

import com.levango7.dataenginebdp.flinkcdc.sink.SinkConfig;
import com.levango7.dataenginebdp.flinkcdc.source.SourceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CdcFramework} 单元测试。
 *
 * <p>测试覆盖 Builder 构造、链式 API、配置校验等不依赖 Flink 运行时的逻辑。
 * 实际 Flink 作业执行（execute/buildPipeline）需要 Flink 集群环境，属于集成测试范畴。</p>
 *
 * @author shuqing-bigdata
 */
class CdcFrameworkTest {

    private SourceConfig sampleSource() {
        return new SourceConfig.Builder()
                .name("src").type(SourceConfig.SourceType.MYSQL)
                .host("h").username("u").password("p")
                .database("d").table("d.t").serverId(1)
                .build();
    }

    private SinkConfig sampleSink() {
        SinkConfig sink = new SinkConfig();
        sink.setName("sink");
        sink.setType(SinkConfig.SinkType.KAFKA);
        sink.setHost("h");
        sink.setTopic("t");
        return sink;
    }

    @Nested
    @DisplayName("Builder — 链式构造")
    class BuilderTest {

        @Test
        @DisplayName("builder() — 默认作业名")
        void builder_defaultJobName() {
            CdcFramework framework = CdcFramework.builder().build();
            assertThat(framework.getJobName()).isEqualTo("flink-cdc-job");
        }

        @Test
        @DisplayName("builder(jobName) — 指定作业名")
        void builder_withJobName() {
            CdcFramework framework = CdcFramework.builder("my-job").build();
            assertThat(framework.getJobName()).isEqualTo("my-job");
        }

        @Test
        @DisplayName("Builder.jobName — 设置作业名")
        void builder_jobName() {
            CdcFramework framework = CdcFramework.builder().jobName("custom").build();
            assertThat(framework.getJobName()).isEqualTo("custom");
        }

        @Test
        @DisplayName("Builder.addSource/addSink — 添加配置")
        void builder_addSourceAndSink() {
            SourceConfig src = sampleSource();
            SinkConfig sink = sampleSink();

            CdcFramework framework = CdcFramework.builder()
                    .jobName("test")
                    .addSource(src)
                    .addSink(sink)
                    .build();

            assertThat(framework.getSources()).hasSize(1);
            assertThat(framework.getSinks()).hasSize(1);
            assertThat(framework.getSources().get(0).getName()).isEqualTo("src");
            assertThat(framework.getSinks().get(0).getName()).isEqualTo("sink");
        }

        @Test
        @DisplayName("Builder.parallelism — 设置并行度")
        void builder_parallelism() {
            CdcFramework framework = CdcFramework.builder().parallelism(8).build();
            assertThat(framework.getParallelism()).isEqualTo(8);
        }

        @Test
        @DisplayName("Builder.blocking — 设置阻塞模式")
        void builder_blocking() {
            CdcFramework framework = CdcFramework.builder().blocking(false).build();
            assertThat(framework.isBlocking()).isFalse();
        }

        @Test
        @DisplayName("Builder.addSource(null) — 抛出 NPE")
        void builder_addSourceNull_throws() {
            assertThatThrownBy(() -> CdcFramework.builder().addSource(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Builder.addSink(null) — 抛出 NPE")
        void builder_addSinkNull_throws() {
            assertThatThrownBy(() -> CdcFramework.builder().addSink(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("链式 addSource/addSink")
    class ChainingTest {

        @Test
        @DisplayName("addSource — 返回 this，支持链式")
        void addSource_returnsThis() {
            CdcFramework framework = CdcFramework.builder("job").build();
            CdcFramework returned = framework.addSource(sampleSource());
            assertThat(returned).isSameAs(framework);
            assertThat(framework.getSources()).hasSize(1);
        }

        @Test
        @DisplayName("addSink — 返回 this，支持链式")
        void addSink_returnsThis() {
            CdcFramework framework = CdcFramework.builder("job").build();
            CdcFramework returned = framework.addSink(sampleSink());
            assertThat(returned).isSameAs(framework);
            assertThat(framework.getSinks()).hasSize(1);
        }

        @Test
        @DisplayName("多次 addSource/addSink — 累加")
        void multipleAdds_accumulate() {
            CdcFramework framework = CdcFramework.builder("job")
                    .addSource(sampleSource())
                    .addSource(sampleSource())
                    .addSink(sampleSink())
                    .addSink(sampleSink())
                    .addSink(sampleSink())
                    .build();

            assertThat(framework.getSources()).hasSize(2);
            assertThat(framework.getSinks()).hasSize(3);
        }

        @Test
        @DisplayName("addSource(null) — 抛出 NPE")
        void addSourceNull_throws() {
            CdcFramework framework = CdcFramework.builder("job").build();
            assertThatThrownBy(() -> framework.addSource(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("parallelism/blocking 链式 — 正确设置")
        void parallelismAndBlocking_chaining() {
            CdcFramework framework = CdcFramework.builder("job").build();
            framework.parallelism(4).blocking(false);
            assertThat(framework.getParallelism()).isEqualTo(4);
            assertThat(framework.isBlocking()).isFalse();
        }
    }

    @Nested
    @DisplayName("buildPipeline — 配置校验")
    class BuildPipelineValidationTest {

        @Test
        @DisplayName("无 Source — 抛出 IllegalStateException")
        void buildPipeline_noSource_throws() {
            CdcFramework framework = CdcFramework.builder("job")
                    .addSink(sampleSink())
                    .build();
            assertThatThrownBy(() -> framework.buildPipeline(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Source");
        }

        @Test
        @DisplayName("无 Sink — 抛出 IllegalStateException")
        void buildPipeline_noSink_throws() {
            CdcFramework framework = CdcFramework.builder("job")
                    .addSource(sampleSource())
                    .build();
            assertThatThrownBy(() -> framework.buildPipeline(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Sink");
        }

        @Test
        @DisplayName("无 Source 无 Sink — 优先报 Source")
        void buildPipeline_noSourceNoSink_throwsForSourceFirst() {
            CdcFramework framework = CdcFramework.builder("job").build();
            assertThatThrownBy(() -> framework.buildPipeline(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Source");
        }
    }

    @Nested
    @DisplayName("只读访问器")
    class AccessorTest {

        @Test
        @DisplayName("getSources — 返回不可修改列表")
        void getSources_unmodifiable() {
            CdcFramework framework = CdcFramework.builder("job")
                    .addSource(sampleSource())
                    .build();
            assertThatThrownBy(() -> framework.getSources().add(sampleSource()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("getSinks — 返回不可修改列表")
        void getSinks_unmodifiable() {
            CdcFramework framework = CdcFramework.builder("job")
                    .addSink(sampleSink())
                    .build();
            assertThatThrownBy(() -> framework.getSinks().add(sampleSink()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("getJobName — 返回构造时的名称")
        void getJobName() {
            CdcFramework framework = CdcFramework.builder("my-cdc-job").build();
            assertThat(framework.getJobName()).isEqualTo("my-cdc-job");
        }
    }
}