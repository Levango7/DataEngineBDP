package com.levango7.dataenginebdp.storage.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 对象元数据快照。
 *
 * <p>由 {@link ObjectStore#statObject(String)} 返回，用于：
 * <ul>
 *   <li>跨域复制时判定冲突（{@code lastModified} 为 LWW 的版本依据）</li>
 *   <li>流式复制前获知对象长度（{@code size}），避免全量读入内存</li>
 *   <li>复制后校验（{@code etag} 仅作辅助，权威校验以客户端 sha256 为准）</li>
 * </ul>
 *
 * <p><b>隐式依赖</b>：{@code lastModified} 语义来自 S3 {@code HeadObject}，秒级精度。
 * LWW 冲突解决基于该字段，因此同一秒内的写入视为同版本（源优先）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectMetadata {

    /** 相对对象键（已剥离租户前缀）。 */
    private String key;

    /** 对象字节数。 */
    private long size;

    /** S3 ETag（非分片上传时为 MD5；分片上传时非 MD5，勿用于内容校验）。 */
    private String etag;

    /** 最后修改时间（S3 HeadObject 语义，秒级精度）。 */
    private Instant lastModified;

    /** MIME 类型。 */
    private String contentType;
}
