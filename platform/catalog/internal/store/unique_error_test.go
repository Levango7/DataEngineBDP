package store

import (
	"errors"
	"fmt"
	"testing"

	"github.com/stretchr/testify/assert"
	"gorm.io/gorm"
)

func TestIsUniqueConstraintError_SQLite(t *testing.T) {
	assert.True(t, isUniqueConstraintError(errors.New("UNIQUE constraint failed: databases.id")))
	assert.True(t, isUniqueConstraintError(errors.New("unique constraint failed: indexes.idx_tenant_dbname")))
}

func TestIsUniqueConstraintError_Postgres(t *testing.T) {
	assert.True(t, isUniqueConstraintError(errors.New(`ERROR: duplicate key value violates unique constraint "idx_tenant_dbname" (SQLSTATE 23505)`)))
	assert.True(t, isUniqueConstraintError(errors.New("Duplicate Key Value Violates Unique Constraint")))
}

func TestIsUniqueConstraintError_Wrapped(t *testing.T) {
	wrapped := fmt.Errorf("insert failed: %w", errors.New("UNIQUE constraint failed: tables.id"))
	assert.True(t, isUniqueConstraintError(wrapped))
}

func TestIsUniqueConstraintError_OtherErrors(t *testing.T) {
	assert.False(t, isUniqueConstraintError(nil))
	assert.False(t, isUniqueConstraintError(gorm.ErrRecordNotFound))
	assert.False(t, isUniqueConstraintError(errors.New("connection refused")))
	assert.False(t, isUniqueConstraintError(errors.New("disk I/O error")))
}
