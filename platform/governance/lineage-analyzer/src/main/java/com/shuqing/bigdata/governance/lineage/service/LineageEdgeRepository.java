package com.shuqing.bigdata.governance.lineage.service;

import com.shuqing.bigdata.governance.lineage.model.LineageEdge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 血缘边 JPA Repository。
 *
 * @author shuqing-bigdata
 */
@Repository
public interface LineageEdgeRepository extends JpaRepository<LineageEdge, Long> {

    /**
     * 查询指定源表的所有下游边。
     *
     * @param sourceFullName 源表全名
     * @return 边列表
     */
    List<LineageEdge> findBySourceFullName(String sourceFullName);

    /**
     * 查询指定目标表的所有上游边。
     *
     * @param targetFullName 目标表全名
     * @return 边列表
     */
    List<LineageEdge> findByTargetFullName(String targetFullName);

    /**
     * 查询指定源表和关系类型的边。
     *
     * @param sourceFullName 源表全名
     * @param relationType   关系类型
     * @return 边列表
     */
    List<LineageEdge> findBySourceFullNameAndRelationType(
            String sourceFullName, LineageEdge.RelationType relationType);

    /**
     * 查询指定目标表和关系类型的边。
     *
     * @param targetFullName 目标表全名
     * @param relationType   关系类型
     * @return 边列表
     */
    List<LineageEdge> findByTargetFullNameAndRelationType(
            String targetFullName, LineageEdge.RelationType relationType);

    /**
     * 查询所有表级边。
     *
     * @return 表级边列表
     */
    List<LineageEdge> findByRelationType(LineageEdge.RelationType relationType);
}