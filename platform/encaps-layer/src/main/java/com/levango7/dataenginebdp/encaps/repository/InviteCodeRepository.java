package com.levango7.dataenginebdp.encaps.repository;

import com.levango7.dataenginebdp.encaps.model.InviteCode;
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
}
