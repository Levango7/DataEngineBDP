package com.levango7.dataenginebdp.infra.xinchang.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 信创节点规格。
 *
 * <p>描述一台物理机/虚拟机的硬件与系统选型，用于 IPMI 带外管理 + PXE 装机。
 * 支持的国产 CPU 架构与操作系统由 {@link CpuArch} 和 {@link OsType} 枚举约束。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class XinchangNodeSpec {

    /**
     * 国产 CPU 架构枚举。
     */
    public enum CpuArch {
        /** 鲲鹏 920（ARMv8.2，aarch64） */
        KUNPENG,
        /** 海光 C86（x86_64 兼容） */
        HYGON,
        /** 飞腾（ARMv8，aarch64） */
        PHYTIUM,
        /** 兆芯（x86_64 兼容） */
        ZHAOXIN
    }

    /**
     * 国产操作系统枚举。
     */
    public enum OsType {
        /** 麒麟 V10 服务器版 */
        KYLIN_V10,
        /** 统信 UOS 服务器版 */
        UOS
    }

    /**
     * 节点角色：control-plane / worker。
     */
    @NotBlank
    private String role;

    /**
     * CPU 架构，默认鲲鹏 920。
     */
    @NotNull
    @Builder.Default
    private CpuArch cpuArch = CpuArch.KUNPENG;

    /**
     * 操作系统，默认麒麟 V10。
     */
    @NotNull
    @Builder.Default
    private OsType osType = OsType.KYLIN_V10;

    /**
     * BMC（带外管理）IP 地址，用于 IPMI Redfish 调用。
     */
    @NotBlank
    private String bmcIp;

    /**
     * BMC 用户名；为空则使用全局默认 {@code app.xinchang.ipmi.username}。
     */
    private String bmcUsername;

    /**
     * BMC 密码；为空则使用全局默认 {@code app.xinchang.ipmi.password}。
     */
    private String bmcPassword;

    /**
     * PXE 引导 MAC 地址，用于绑定装机目标。
     */
    @NotBlank
    private String pxeMac;

    /**
     * 节点主机名（装机后设置）。
     */
    @NotBlank
    private String hostname;

    /**
     * CPU 核数（仅用于规格校验/记录，不直接参与装机）。
     */
    private int cpuCores;

    /**
     * 内存 GB（仅用于规格校验/记录）。
     */
    private int memoryGb;

    /**
     * 系统盘 GB（仅用于规格校验/记录）。
     */
    private int diskGb;
}