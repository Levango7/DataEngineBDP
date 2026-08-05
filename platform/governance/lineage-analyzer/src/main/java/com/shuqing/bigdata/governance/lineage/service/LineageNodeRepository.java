package com.shuqing.bigdata.governance.lineage.service;

import com.shuqing.bigdata.governance.lineage.model.LineageNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 血缘节点 JPA Repository。
 *
 * @author shuqing-bigdata
 */
@Repository
public interface LineageNodeRepository extends JpaRepository<LineageNode, Long> {

    /**
     * 按全名查找节点。
     *
     * @param fullName 全名
     * @return 节点 Optional
     */
    Optional<LineageNode> findByFullName(String fullName);
}