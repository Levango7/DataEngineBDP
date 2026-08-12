package com.levango7.dataenginebdp.infra.cloud.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * VM 规格定义。
 *
 * <p>统一封装三朵云的实例规格字段，由各 Provider 翻译为云原生 SDK 的 flavors：
 * <ul>
 *   <li>华为云：{@code flavorRef}（如 s6.large.2）</li>
 *   <li>阿里云：{@code InstanceType}（如 ecs.g6.large）</li>
 *   <li>腾讯云：{@code InstanceType}（如 S5.LARGE8）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VMSpec {

    /** 实例规格 ID（云原生 flavor 标识，如 s6.large.2 / ecs.g6.large / S5.LARGE8） */
    @NotBlank(message = "instanceType 不能为空")
    private String instanceType;

    /** 镜像 ID（云原生 image 标识） */
    @NotBlank(message = "imageId 不能为空")
    private String imageId;

    /** 系统盘大小（GB） */
    @NotNull(message = "systemDiskGb 不能为空")
    @Min(value = 40, message = "systemDiskGb 至少 40GB")
    private Integer systemDiskGb;

    /** 数据盘大小（GB），0 表示不创建数据盘 */
    @Builder.Default
    private Integer dataDiskGb = 0;

    /** 带宽（Mbps），0 表示不分配公网 IP */
    @Builder.Default
    private Integer bandwidthMbps = 5;

    /** 是否分配公网 IP / EIP */
    @Builder.Default
    private boolean allocatePublicIp = true;

    /** SSH 登录用户名（如 root / ubuntu） */
    @Builder.Default
    private String sshUsername = "root";

    /** SSH 公钥（用于注入 authorized_keys） */
    private String sshPublicKey;
}