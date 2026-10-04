package com.levango7.dataenginebdp.storage.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ObjectMetadata 单元测试：构建、值语义相等、缺省值。
 *
 * <p>依赖说明：被测类无外部依赖（值对象），本测试不涉及 Mock 注入。
 */
class ObjectMetadataTest {

    @Test
    void builder_populatesAllFields() {
        Instant now = Instant.parse("2026-10-05T00:00:00Z");
        ObjectMetadata meta = ObjectMetadata.builder()
                .key("_system/x/a.bin")
                .size(1024L)
                .etag("d41d8cd98f00b204e9800998ecf8427e")
                .lastModified(now)
                .contentType("application/octet-stream")
                .build();

        assertThat(meta.getKey()).isEqualTo("_system/x/a.bin");
        assertThat(meta.getSize()).isEqualTo(1024L);
        assertThat(meta.getEtag()).isEqualTo("d41d8cd98f00b204e9800998ecf8427e");
        assertThat(meta.getLastModified()).isEqualTo(now);
        assertThat(meta.getContentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void equalsAndHashCode_byValue() {
        Instant now = Instant.parse("2026-10-05T00:00:00Z");
        ObjectMetadata a = ObjectMetadata.builder().key("k").size(1L).lastModified(now).build();
        ObjectMetadata b = ObjectMetadata.builder().key("k").size(1L).lastModified(now).build();
        ObjectMetadata c = ObjectMetadata.builder().key("k").size(2L).lastModified(now).build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void unsetFields_defaultToNullAndZero() {
        ObjectMetadata meta = ObjectMetadata.builder().key("k").build();

        assertThat(meta.getSize()).isZero();
        assertThat(meta.getEtag()).isNull();
        assertThat(meta.getLastModified()).isNull();
        assertThat(meta.getContentType()).isNull();
    }
}
