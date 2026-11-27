package gateway

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Levango7/DataEngineBDP/llm-gateway/internal/provider"
)

func TestAuditor_CheckResponse_BlocksSensitive(t *testing.T) {
	auditor := NewAuditor([]string{"password"}, "")
	resp := &provider.ChatResponse{
		Choices: []provider.Choice{
			{Message: provider.Message{Role: "assistant", Content: "your password is 123456"}},
		},
	}
	err := auditor.CheckResponse("t1", "u1", "m1", resp)
	assert.ErrorIs(t, err, provider.ErrSensitiveContent)
}

func TestAuditor_CheckResponse_AllowsClean(t *testing.T) {
	auditor := NewAuditor([]string{"password"}, "")
	resp := &provider.ChatResponse{
		Choices: []provider.Choice{
			{Message: provider.Message{Role: "assistant", Content: "hello"}},
		},
	}
	assert.NoError(t, auditor.CheckResponse("t1", "u1", "m1", resp))
}

func TestAuditor_RecordBlockedChat_PersistsJsonl(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "audit.jsonl")
	auditor := NewAuditor([]string{"password"}, path)

	resp := &provider.ChatResponse{
		Choices: []provider.Choice{
			{Message: provider.Message{Role: "assistant", Content: "your password is 123456"}},
		},
		Usage: provider.Usage{TotalTokens: 7},
	}
	auditor.RecordBlockedChat("t1", "u1", "m1", resp, provider.ErrSensitiveContent, time.Second)

	raw, err := os.ReadFile(path)
	require.NoError(t, err)
	line := strings.TrimSpace(string(raw))
	assert.Contains(t, line, `"blocked":true`)
	assert.Contains(t, line, `"error":"sensitive content detected"`)
}

func TestAuditor_RecordChat_PersistsJsonl(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "audit.jsonl")
	auditor := NewAuditor(nil, path)

	auditor.RecordChat("t1", "u1", "m1", nil, nil, time.Second)

	raw, err := os.ReadFile(path)
	require.NoError(t, err)
	assert.Contains(t, string(raw), `"blocked":false`)
}
