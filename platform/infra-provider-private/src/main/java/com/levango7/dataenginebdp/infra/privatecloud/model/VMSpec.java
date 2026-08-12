package com.levango7.dataenginebdp.infra.privatecloud.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * VM 规格定义。
 *
 * <p>描述一台虚拟机的资源规格：CPU 核数、内存（MB）、系统盘大小（GB）、
 * 数据盘列表、网络名称、镜像/模板引用等。由 {@code PrivateClusterRequest}
 * 引用，传给 {@code PrivateCloudProvider} 创建 VM。</p>
 *
 * <p>该 POJO 不直接持久化，作为请求体的一部分由 {@code PrivateClusterInfo}
 * 序列化为 JSON 列存储。</p>
 *
 * @author shuqing-bigdata
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VMSpec {

    /** VM 角色标签：control-plane / worker */
    private String role;

    /** CPU 核数 */
    private Integer cpu;

    /** 内存大小（MB） */
    private Integer memoryMb;

    /** 系统盘大小（GB） */
    private Integer systemDiskGb;

    /** 数据盘大小（GB），0 表示无数据盘 */
    private Integer dataDiskGb;

    /** 网络/端口组名称（vSphere）或网络 ID（OpenStack） */
    private String network;

    /** 镜像 ID（OpenStack）或模板 VM 名称（vSphere）；为空则使用全局默认 */
    private String imageRef;

    /** flavor ID（OpenStack 专用；vSphere 忽略） */
    private String flavorId;

    /** 主机名前缀，最终主机名形如 {@code <prefix>-<index>} */
    private String hostnamePrefix;

    /** 云平台侧附加标签（key=value），透传到 vSphere tag 或 OpenStack metadata */
    private java.util.Map<String, String> tags;
}