package com.shuqing.bigdata.governance.collector.repository;

import com.shuqing.bigdata.governance.collector.model.CollectionHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 采集历史 Repository。
 *
 * <p>提供按数据源 ID 查询历史记录的便捷方法，用于状态查询与审计。</p>
 */
@Repository
public interface CollectionHistoryRepository extends JpaRepository<CollectionHistory, Long> {

    /**
     * 按数据源 ID 查询采集历史，按开始时间倒序。
     *
     * @param sourceId 数据源 ID
     * @return 历史记录列表
     */
    List<CollectionHistory> findBySourceIdOrderByStartedAtDesc(Long sourceId);
}