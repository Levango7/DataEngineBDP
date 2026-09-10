package com.levango7.dataenginebdp.encaps.repository;

import com.levango7.dataenginebdp.encaps.model.UserRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户注册审批仓储。
 */
@Repository
public interface UserRegistrationRepository extends JpaRepository<UserRegistration, Long> {

    Optional<UserRegistration> findByUsername(String username);

    List<UserRegistration> findByTenantIdOrderByCreatedAtDesc(Long tenantId);

    List<UserRegistration> findByStatusOrderByCreatedAtDesc(String status);
}
