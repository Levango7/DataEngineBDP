package com.levango7.dataenginebdp.encaps.repository;

import com.levango7.dataenginebdp.encaps.model.DevelopScheduleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 数据开发调度任务仓储。
 */
@Repository
public interface DevelopScheduleRepository extends JpaRepository<DevelopScheduleEntity, Long> {

    /**
     * 按租户 ID 查询全部调度任务。
     */
    List<DevelopScheduleEntity> findByTenantId(String tenantId);

    /**
     * 按文件路径查询调度任务（DAG 解析时使用）。
     */
    List<DevelopScheduleEntity> findByFilePath(String filePath);

    /**
     * 按租户 + 文件路径查询。
     */
    List<DevelopScheduleEntity> findByTenantIdAndFilePath(String tenantId, String filePath);
}