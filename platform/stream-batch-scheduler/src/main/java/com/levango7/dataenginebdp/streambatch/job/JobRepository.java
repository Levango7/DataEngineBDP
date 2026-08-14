package com.levango7.dataenginebdp.streambatch.job;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 作业仓储。
 */
@Repository
public interface JobRepository extends JpaRepository<JobEntity, Long> {

    Page<JobEntity> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId, Pageable pageable);
}
