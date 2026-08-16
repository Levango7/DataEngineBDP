package com.levango7.dataenginebdp.encaps.repository;

import com.levango7.dataenginebdp.encaps.model.EvalMetricEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 大模型评估指标仓储。
 */
@Repository
public interface EvalMetricRepository extends JpaRepository<EvalMetricEntity, Long> {

    /** 列出租户下全部评估指标（按创建时间倒序）。 */
    List<EvalMetricEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    /** 按模型名过滤。 */
    List<EvalMetricEntity> findByTenantIdAndModelNameOrderByCreatedAtDesc(
            String tenantId, String modelName);

    /** 按评估类型过滤。 */
    List<EvalMetricEntity> findByTenantIdAndEvalTypeOrderByCreatedAtDesc(
            String tenantId, String evalType);
}