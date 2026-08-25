package model

import "time"

// Database 描述一个数据库（命名空间）的元数据。
// 在 Catalog 中，Database 是 Table 的逻辑分组容器。
//
// GORM 标签用于持久化：
//   - ID 为字符串主键（UUID，由应用层生成）
//   - TenantID 为租户隔离键，名称唯一性按租户收敛（idx_tenant_name）
type Database struct {
	ID          string    `json:"id" gorm:"primaryKey"`
	TenantID    string    `json:"tenantId" gorm:"index:idx_tenant_dbname,unique"`
	Name        string    `json:"name" gorm:"index:idx_tenant_dbname,unique"`
	Description string    `json:"description,omitempty"`
	Owner       string    `json:"owner"`
	CreatedAt   time.Time `json:"createdAt"`
}
