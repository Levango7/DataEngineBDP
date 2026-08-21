package com.shuqing.bigdata.infra.orchestrator.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ClusterInfo} 单元测试，重点覆盖状态归一化。
 */
class ClusterInfoTest {

    @Test
    void normalizeStatusShouldMapCreatingVariants() {
        assertThat(ClusterInfo.normalizeStatus("CREATING")).isEqualTo(ClusterInfo.Status.CREATING);
        assertThat(ClusterInfo.normalizeStatus("pending")).isEqualTo(ClusterInfo.Status.CREATING);
        assertThat(ClusterInfo.normalizeStatus("PROVISIONING")).isEqualTo(ClusterInfo.Status.CREATING);
    }

    @Test
    void normalizeStatusShouldMapActiveVariants() {
        assertThat(ClusterInfo.normalizeStatus("ACTIVE")).isEqualTo(ClusterInfo.Status.ACTIVE);
        assertThat(ClusterInfo.normalizeStatus("running")).isEqualTo(ClusterInfo.Status.ACTIVE);
        assertThat(ClusterInfo.normalizeStatus("READY")).isEqualTo(ClusterInfo.Status.ACTIVE);
    }

    @Test
    void normalizeStatusShouldMapScalingVariants() {
        assertThat(ClusterInfo.normalizeStatus("SCALING")).isEqualTo(ClusterInfo.Status.SCALING);
        assertThat(ClusterInfo.normalizeStatus("UPDATING")).isEqualTo(ClusterInfo.Status.SCALING);
        assertThat(ClusterInfo.normalizeStatus("resizing")).isEqualTo(ClusterInfo.Status.SCALING);
    }

    @Test
    void normalizeStatusShouldMapDestroyingVariants() {
        assertThat(ClusterInfo.normalizeStatus("DESTROYING")).isEqualTo(ClusterInfo.Status.DESTROYING);
        assertThat(ClusterInfo.normalizeStatus("DELETING")).isEqualTo(ClusterInfo.Status.DESTROYING);
        assertThat(ClusterInfo.normalizeStatus("TERMINATING")).isEqualTo(ClusterInfo.Status.DESTROYING);
    }

    @Test
    void normalizeStatusShouldMapDestroyedVariants() {
        assertThat(ClusterInfo.normalizeStatus("DESTROYED")).isEqualTo(ClusterInfo.Status.DESTROYED);
        assertThat(ClusterInfo.normalizeStatus("DELETED")).isEqualTo(ClusterInfo.Status.DESTROYED);
        assertThat(ClusterInfo.normalizeStatus("terminated")).isEqualTo(ClusterInfo.Status.DESTROYED);
    }

    @Test
    void normalizeStatusShouldMapFailedVariants() {
        assertThat(ClusterInfo.normalizeStatus("FAILED")).isEqualTo(ClusterInfo.Status.FAILED);
        assertThat(ClusterInfo.normalizeStatus("error")).isEqualTo(ClusterInfo.Status.FAILED);
    }

    @Test
    void normalizeStatusShouldReturnUnknownForUnrecognized() {
        assertThat(ClusterInfo.normalizeStatus("WEIRD")).isEqualTo(ClusterInfo.Status.UNKNOWN);
        assertThat(ClusterInfo.normalizeStatus("")).isEqualTo(ClusterInfo.Status.UNKNOWN);
        assertThat(ClusterInfo.normalizeStatus(null)).isEqualTo(ClusterInfo.Status.UNKNOWN);
    }
}