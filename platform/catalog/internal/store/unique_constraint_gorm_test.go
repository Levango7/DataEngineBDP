
package store

import (
	"testing"
	"time"

	"github.com/glebarez/sqlite"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"gorm.io/gorm"

	"github.com/Levango7/DataEngineBDP/catalog/internal/model"
)

func TestGormStore_CreateDatabase_UniqueViolationInCreateWindow(t *testing.T) {
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	require.NoError(t, err)
	require.NoError(t, db.AutoMigrate(&model.Database{}))
	s := NewGormStore(db)

	require.NoError(t, db.Callback().Create().Before("gorm:create").Register("test:inject_conflict", func(tx *gorm.DB) {
		tx.Exec("INSERT INTO databases (id, tenant_id, name, owner, created_at) VALUES (?, ?, ?, ?, ?)",
			"race-001", "t1", "shadow", "", time.Now().UTC())
	}))

	target := &model.Database{TenantID: "t1", ID: "race-001", Name: "main", CreatedAt: time.Now().UTC()}
	err = s.CreateDatabase(target)
	assert.ErrorIs(t, err, ErrAlreadyExists)
}

func TestGormStore_CreateTable_UniqueViolationInCreateWindow(t *testing.T) {
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	require.NoError(t, err)
	require.NoError(t, db.AutoMigrate(&model.Table{}))
	s := NewGormStore(db)

	require.NoError(t, db.Callback().Create().Before("gorm:create").Register("test:inject_conflict", func(tx *gorm.DB) {
		tx.Exec("INSERT INTO tables (id, tenant_id, database_name, table_name, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
			"race-tbl", "t1", "db1", "shadow", time.Now().UTC(), time.Now().UTC())
	}))

	target := &model.Table{TenantID: "t1", ID: "race-tbl", DatabaseName: "db1", TableName: "main",
		CreatedAt: time.Now().UTC(), UpdatedAt: time.Now().UTC()}
	err = s.CreateTable(target)
	assert.ErrorIs(t, err, ErrAlreadyExists)
}
