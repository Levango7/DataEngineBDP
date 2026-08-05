package com.shuqing.bigdata.governance.collector.repository;

import com.shuqing.bigdata.governance.collector.model.MetadataSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 元数据采集源 Repository。
 *
 * <p>Spring Data JPA 自动生成实现，提供按名称查询等便捷方法。</p>
 */
@Repository
public interface MetadataSourceRepository extends JpaRepository<MetadataSource, Long> {

    /**
     * 按名称查找数据源。
     *
     * @param name 数据源名称
     * @return 数据源 Optional
     */
    Optional<MetadataSource> findByName(String name);
}