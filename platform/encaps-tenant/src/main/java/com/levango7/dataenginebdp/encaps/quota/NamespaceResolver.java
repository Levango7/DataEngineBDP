package com.levango7.dataenginebdp.encaps.quota;

/**
 * Workspace ID → K8s Namespace 名称解析器。
 *
 * <p>Quota 翻译器需要知道 Workspace 对应的 K8s Namespace 名称才能下发 ResourceQuota/LimitRange。
 * 此接口解耦 Quota 模块与 Workspace 模块：Quota 不直接依赖 Workspace 实体，
 * 而是通过此接口由外部（默认实现 {@link WorkspaceNamespaceResolver}）提供解析能力。</p>
 */
public interface NamespaceResolver {

    /**
     * 解析 Workspace ID 对应的 K8s Namespace 名称。
     *
     * @param workspaceId Workspace ID
     * @return K8s Namespace 名称；若 Workspace 不存在或未分配 Namespace 返回 null
     */
    String resolve(Long workspaceId);
}