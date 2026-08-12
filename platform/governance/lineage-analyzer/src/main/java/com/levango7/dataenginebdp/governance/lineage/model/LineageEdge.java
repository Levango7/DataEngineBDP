package com.levango7.dataenginebdp.governance.lineage.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;

/**
 * 血缘图边（依赖关系）。
 *
 * <p>边从源节点（上游）指向目标节点（下游），表示目标节点的数据来源于源节点。
 * 一条 INSERT INTO t2 SELECT FROM t1 会产生一条 {@code t1 → t2} 的边。</p>
 *
 * @author shuqing-bigdata
 */
@Entity
@Table(name = "lineage_edge",
        indexes = {
                @Index(name = "idx_edge_source", columnList = "source_full_name"),
                @Index(name = "idx_edge_target", columnList = "target_full_name"),
                @Index(name = "idx_edge_relation_type", columnList = "relation_type")
        },
        uniqueConstraints = @UniqueConstraint(name = "uk_edge_pair",
                columnNames = {"source_full_name", "target_full_name", "relation_type"}))
public class LineageEdge {

    /** 关系类型 */
    public enum RelationType {
        /** 表级血缘 */
        TABLE_LINEAGE,
        /** 字段级血缘 */
        COLUMN_LINEAGE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 源节点全名（上游） */
    @Column(name = "source_full_name", nullable = false, length = 512)
    private String sourceFullName;

    /** 目标节点全名（下游） */
    @Column(name = "target_full_name", nullable = false, length = 512)
    private String targetFullName;

    /** 关系类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", nullable = false, length = 32)
    private RelationType relationType;

    /** 产生此血缘的 SQL 文本（可选，便于追溯） */
    @Column(name = "source_sql", length = 4096)
    private String sourceSql;

    /** SQL 方言 */
    @Column(name = "dialect", length = 16)
    private String dialect;

    /** 转换表达式（字段级血缘：target = expr(source)） */
    @Column(name = "expression", length = 1024)
    private String expression;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * 默认构造（JPA 要求）。
     */
    public LineageEdge() {
    }

    /**
     * 构造边。
     *
     * @param sourceFullName 源节点全名
     * @param targetFullName 目标节点全名
     * @param relationType   关系类型
     */
    public LineageEdge(String sourceFullName, String targetFullName, RelationType relationType) {
        this.sourceFullName = sourceFullName;
        this.targetFullName = targetFullName;
        this.relationType = relationType;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSourceFullName() {
        return sourceFullName;
    }

    public void setSourceFullName(String sourceFullName) {
        this.sourceFullName = sourceFullName;
    }

    public String getTargetFullName() {
        return targetFullName;
    }

    public void setTargetFullName(String targetFullName) {
        this.targetFullName = targetFullName;
    }

    public RelationType getRelationType() {
        return relationType;
    }

    public void setRelationType(RelationType relationType) {
        this.relationType = relationType;
    }

    public String getSourceSql() {
        return sourceSql;
    }

    public void setSourceSql(String sourceSql) {
        this.sourceSql = sourceSql;
    }

    public String getDialect() {
        return dialect;
    }

    public void setDialect(String dialect) {
        this.dialect = dialect;
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LineageEdge other)) {
            return false;
        }
        return Objects.equals(sourceFullName, other.sourceFullName)
                && Objects.equals(targetFullName, other.targetFullName)
                && relationType == other.relationType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceFullName, targetFullName, relationType);
    }

    @Override
    public String toString() {
        return "LineageEdge{" + sourceFullName + " -> " + targetFullName
                + ", type=" + relationType + '}';
    }
}