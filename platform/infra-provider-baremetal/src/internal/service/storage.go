// Package service - storage.go 提供数据库连接初始化。
//
// 支持SQLite(开发，纯Go驱动)与PostgreSQL(生产)两种后端。
package service

import (
	"fmt"
	"time"

	"github.com/glebarez/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

// InitDatabase 初始化数据库连接
//
// driver: "sqlite" 或 "postgres"
// 对于sqlite，dsn为文件路径；对于postgres，dsn为PostgreSQL连接串
func InitDatabase(driver, dsn string, maxOpen, maxIdle int) (*gorm.DB, error) {
	var db *gorm.DB
	var err error

	gormCfg := &gorm.Config{
		Logger: logger.Default.LogMode(logger.Warn),
	}

	switch driver {
	case "sqlite", "":
		db, err = gorm.Open(sqlite.Open(dsn), gormCfg)
		if err != nil {
			return nil, fmt.Errorf("打开SQLite失败: %w", err)
		}
	case "postgres":
		// 生产环境使用 gorm.io/driver/postgres
		// 此处为避免引入CGO依赖，由调用方在main中注入
		return nil, fmt.Errorf("postgres驱动需在main中显式注入")
	default:
		return nil, fmt.Errorf("不支持的数据库驱动: %s", driver)
	}

	sqlDB, err := db.DB()
	if err != nil {
		return nil, fmt.Errorf("获取底层sql.DB失败: %w", err)
	}
	sqlDB.SetMaxOpenConns(maxOpen)
	sqlDB.SetMaxIdleConns(maxIdle)
	sqlDB.SetConnMaxLifetime(time.Hour)

	return db, nil
}
