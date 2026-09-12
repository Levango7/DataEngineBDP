package com.levango7.dataenginebdp.streambatch.run;

import com.levango7.dataenginebdp.streambatch.model.ExecutionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * DAG 运行历史仓储（Spring Data JPA）。
 */
@Repository
public interface DagRunRepository extends JpaRepository<DagRunEntity, Long> {

    /**
     * 按 DAG ID 分页查询运行历史（按开始时间倒序）。
     *
     * @param dagId    DAG ID
     * @param pageable 分页参数
     * @return 运行历史分页
     */
    Page<DagRunEntity> findByDagIdOrderByStartTimeDesc(String dagId, Pageable pageable);

    /**
     * 按 DAG ID + 状态分页查询。
     *
     * @param dagId    DAG ID
     * @param status   执行状态
     * @param pageable 分页参数
     * @return 运行历史分页
     */
    Page<DagRunEntity> findByDagIdAndStatusOrderByStartTimeDesc(
            String dagId, ExecutionStatus status, Pageable pageable);

    /**
     * 按租户 + DAG ID 分页查询运行历史（租户隔离，按开始时间倒序）。
     *
     * @param tenantId 租户 ID
     * @param dagId    DAG ID
     * @param pageable 分页参数
     * @return 运行历史分页
     */
    Page<DagRunEntity> findByTenantIdAndDagIdOrderByStartTimeDesc(
            String tenantId, String dagId, Pageable pageable);

    /**
     * 按租户 + DAG ID + 状态分页查询（租户隔离）。
     *
     * @param tenantId 租户 ID
     * @param dagId    DAG ID
     * @param status   执行状态
     * @param pageable 分页参数
     * @return 运行历史分页
     */
    Page<DagRunEntity> findByTenantIdAndDagIdAndStatusOrderByStartTimeDesc(
            String tenantId, String dagId, ExecutionStatus status, Pageable pageable);

    /**
     * 按租户 + runId 查询（租户隔离的重跑/补数据前置）。
     *
     * @param tenantId 租户 ID
     * @param id       runId
     * @return 运行记录 Optional
     */
    java.util.Optional<DagRunEntity> findByTenantIdAndId(String tenantId, Long id);

    /**
     * 统计某 DAG 的运行次数。
     *
     * @param dagId DAG ID
     * @return 运行次数
     */
    long countByDagId(String dagId);

    /**
     * 删除某 DAG 的全部历史（运维清理用）。
     *
     * @param dagId DAG ID
     * @return 删除条数
     */
    long deleteByDagId(String dagId);
}
