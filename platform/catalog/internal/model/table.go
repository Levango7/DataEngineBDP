package model

import "time"

// Column 描述表中一列的元数据。
//
// GORM 标签使 Column 可作为 Table 的 serialize 字段（datatypes.JSON 或 text 序列化）。
// 这里使用 `gorm:"-:migration"` 让 GORM 自动迁移时跳过该独立结构，
// 实际通过 Table 的 ColumnsJSON 字段持久化。
type Column struct {
	Name        string `json:"name"`
	Type        string `json:"type"`
	Description string `json:"description,omitempty"`
	Nullable    bool   `json:"nullable"`
}

// Table 描述一张表的元数据，是 Catalog 管理的核心对象。
//
// GORM 持久化策略：
//   - ID 为字符串主键（UUID，由应用层生成）
//   - DatabaseName + TableName 组合唯一索引，保证库内表名唯一
//   - Columns / PartitionKeys / Properties 为复杂结构，使用 GORM serialized 列持久化
//     （基于 gob 编码到 LONGTEXT/LONGVARCHAR）
type Table struct {
	ID            string            `json:"id" gorm:"primaryKey"`
	DatabaseName  string            `json:"databaseName" gorm:"index:idx_db_table,unique"`
	TableName     string            `json:"tableName" gorm:"index:idx_db_table,unique"`
	Description   string            `json:"description,omitempty"`
	Columns       []Column          `json:"columns" gorm:"serializer:json"`
	PartitionKeys []string          `json:"partitionKeys,omitempty" gorm:"serializer:json"`
	Properties    map[string]string `json:"properties,omitempty" gorm:"serializer:json"`
	CreatedAt     time.Time         `json:"createdAt"`
	UpdatedAt     time.Time         `json:"updatedAt"`
}

// SearchResult 描述一次全文检索命中的结果项。
//
// 包含命中的 Table 及其相关性分数（0~1，越高越相关）。
// Score 由 tokenizer.Score 基于 bigram token 交集计算得出。
type SearchResult struct {
	Table *Table  `json:"table"`
	Score float64 `json:"score"`
}
