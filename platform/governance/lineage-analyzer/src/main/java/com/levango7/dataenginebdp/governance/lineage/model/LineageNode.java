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
 * 血缘图节点（表或字段）。
 *
 * <p>每个节点代表一个可被血缘追踪的实体：表级节点（{@link NodeType#TABLE}）
 * 或字段级节点（{@link NodeType#COLUMN}）。节点通过 {@code fullName} 唯一标识，
 * 表节点形如 {@code db.table}，字段节点形如 {@code db.table.column}。</p>
 *
 * @author shuqing-bigdata
 */
@Entity
@Table(name = "lineage_node",
        indexes = {
                @Index(name = "idx_node_full_name", columnList = "full_name"),
                @Index(name = "idx_node_type", columnList = "node_type")
        },
        uniqueConstraints = @UniqueConstraint(name = "uk_node_full_name", columnNames = "full_name"))
public class LineageNode {

    /** 节点类型 */
    public enum NodeType {
        /** 表节点 */
        TABLE,
        /** 字段节点 */
        COLUMN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 节点全名，表：db.table；字段：db.table.column */
    @Column(name = "full_name", nullable = false, length = 512, unique = true)
    private String fullName;

    /** 节点类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", nullable = false, length = 16)
    private NodeType nodeType;

    /** 数据库/Schema 名 */
    @Column(name = "schema_name", length = 128)
    private String schemaName;

    /** 表名 */
    @Column(name = "table_name", length = 128)
    private String tableName;

    /** 字段名（仅 COLUMN 节点有效） */
    @Column(name = "column_name", length = 128)
    private String columnName;

    /** 节点显示名 */
    @Column(name = "display_name", length = 256)
    private String displayName;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 最后更新时间 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 默认构造（JPA 要求）。
     */
    public LineageNode() {
    }

    /**
     * 构造节点。
     *
     * @param fullName  全名
     * @param nodeType  节点类型
     */
    public LineageNode(String fullName, NodeType nodeType) {
        this.fullName = fullName;
        this.nodeType = nodeType;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public NodeType getNodeType() {
        return nodeType;
    }

    public void setNodeType(NodeType nodeType) {
        this.nodeType = nodeType;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LineageNode other)) {
            return false;
        }
        return Objects.equals(fullName, other.fullName)
                && nodeType == other.nodeType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fullName, nodeType);
    }

    @Override
    public String toString() {
        return "LineageNode{" + fullName + ", type=" + nodeType + '}';
    }
}