package com.levango7.dataenginebdp.finops.model;

/**
 * 资源维度枚举。
 *
 * <p>覆盖 FinOps 六维度资源用量采集：</p>
 * <ul>
 *   <li>{@link #CPU} CPU 用量（核时）</li>
 *   <li>{@link #MEMORY} 内存用量（GB·时）</li>
 *   <li>{@link #STORAGE} 存储用量（GB·时）</li>
 *   <li>{@link #GPU} GPU 用量（卡时，按型号差异化计价）</li>
 *   <li>{@link #NETWORK} 网络流量（GB）</li>
 *   <li>{@link #SCANNED_DATA} 查询扫描数据（TB，按查询计量聚合）</li>
 * </ul>
 */
public enum ResourceDimension {

    /** CPU 用量（核时） */
    CPU,

    /** 内存用量（GB·时） */
    MEMORY,

    /** 存储用量（GB·时） */
    STORAGE,

    /** GPU 用量（卡时，按型号差异化计价） */
    GPU,

    /** 网络流量（GB） */
    NETWORK,

    /** 查询扫描数据（TB，按查询计量聚合） */
    SCANNED_DATA
}