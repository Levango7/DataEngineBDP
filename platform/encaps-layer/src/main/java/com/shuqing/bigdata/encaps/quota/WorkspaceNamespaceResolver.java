package com.shuqing.bigdata.encaps.quota;

import com.shuqing.bigdata.encaps.workspace.Workspace;
import com.shuqing.bigdata.encaps.workspace.WorkspaceRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 基于 WorkspaceRepository 的 NamespaceResolver 默认实现。
 *
 * <p>通过 {@link WorkspaceRepository} 查询 Workspace 实体，返回其 {@code namespace} 字段。
 * 使用 {@code @Lazy} 避免与 Workspace 模块的潜在循环依赖。</p>
 */
@Component
public class WorkspaceNamespaceResolver implements NamespaceResolver {

    private final WorkspaceRepository workspaceRepository;

    public WorkspaceNamespaceResolver(@Lazy WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    @Override
    public String resolve(Long workspaceId) {
        if (workspaceId == null) {
            return null;
        }
        Optional<Workspace> opt = workspaceRepository.findById(workspaceId);
        return opt.map(Workspace::getNamespace).orElse(null);
    }
}