package com.levango7.dataenginebdp.encaps.repository;

import com.levango7.dataenginebdp.encaps.model.FinetuneTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 大模型微调任务仓储。
 */
@Repository
public interface FinetuneTaskRepository extends JpaRepository<FinetuneTaskEntity, Long> {

    /** 列出租户下全部微调任务（按提交时间倒序）。 */
    List<FinetuneTaskEntity> findByTenantIdOrderBySubmittedAtDesc(String tenantId);

    /** 按业务 taskId 查询（租户隔离）。 */
    Optional<FinetuneTaskEntity> findByTaskIdAndTenantId(String taskId, String tenantId);

    /** 按状态过滤。 */
    List<FinetuneTaskEntity> findByTenantIdAndStatusOrderBySubmittedAtDesc(
            String tenantId, String status);

    /** 按模型名过滤。 */
    List<FinetuneTaskEntity> findByTenantIdAndModelNameOrderBySubmittedAtDesc(
            String tenantId, String modelName);
}