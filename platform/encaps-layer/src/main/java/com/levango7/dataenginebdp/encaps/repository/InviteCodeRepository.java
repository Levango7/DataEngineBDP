package com.levango7.dataenginebdp.encaps.repository;

import com.levango7.dataenginebdp.encaps.model.InviteCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 邀请码仓储。
 */
@Repository
public interface InviteCodeRepository extends JpaRepository<InviteCode, Long> {

    Optional<InviteCode> findByCode(String code);

    List<InviteCode> findByTenantIdOrderByCreatedAtDesc(Long tenantId);

    List<InviteCode> findByStatusOrderByCreatedAtDesc(String status);

    /**
     * 按租户分页查询邀请码（R8 修复：list 端点分页支持）。
     *
     * @param tenantId 租户 ID
     * @param pageable 分页参数
     * @return 邀请码分页结果
     */
    Page<InviteCode> findByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);

    /**
     * 按状态分页查询邀请码（R8 修复：list 端点分页支持）。
     *
     * @param status 状态
     * @param pageable 分页参数
     * @return 邀请码分页结果
     */
    Page<InviteCode> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
}
