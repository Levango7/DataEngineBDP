// Package service - bmc_guard_test.go BMC 出网地址门禁单元测试。
package service

import (
	"strings"
	"testing"
)

func TestValidateBMCBase(t *testing.T) {
	tests := []struct {
		name    string
		raw     string
		lab     string
		want    string
		wantErr string
	}{
		{name: "裸IP默认https", raw: "10.0.0.1", want: "https://10.0.0.1/redfish/v1"},
		{name: "带端口", raw: "10.0.0.1:443", want: "https://10.0.0.1:443/redfish/v1"},
		{name: "主机名", raw: "bmc01.example.com", want: "https://bmc01.example.com/redfish/v1"},
		{name: "显式https", raw: "https://bmc.example.com", want: "https://bmc.example.com/redfish/v1"},
		{name: "方括号IPv6", raw: "[fd00::2]", want: "https://[fd00::2]/redfish/v1"},
		{name: "拒绝http明文", raw: "http://10.0.0.1", wantErr: "http 明文"},
		{name: "拒绝非http协议", raw: "ftp://10.0.0.1", wantErr: "不受支持"},
		{name: "拒绝回环", raw: "127.0.0.1", wantErr: "回环地址"},
		{name: "拒绝云元数据", raw: "169.254.169.254", wantErr: "链路本地段"},
		{name: "云元数据即使实验室也拒", raw: "169.254.169.254", lab: "true", wantErr: "链路本地段"},
		{name: "拒绝fe80链路本地", raw: "fe80::1", lab: "true", wantErr: "链路本地段"},
		{name: "拒绝未指定地址", raw: "0.0.0.0", wantErr: "未指定地址"},
		{name: "拒绝路径注入", raw: "10.0.0.1/admin", wantErr: "host[:port]"},
		{name: "拒绝查询注入", raw: "10.0.0.1?next=evil", wantErr: "host[:port]"},
		{name: "拒绝凭据注入", raw: "evil.com@10.0.0.1", wantErr: "host[:port]"},
		{name: "拒绝空地址", raw: "   ", wantErr: "为空"},
		{name: "实验室放行http回环", raw: "http://127.0.0.1:8080", lab: "true", want: "http://127.0.0.1:8080/redfish/v1"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Setenv(envBMCHostAllowlist, "")
			if tt.lab != "" {
				t.Setenv(envAllowInsecureBMC, tt.lab)
			}
			got, err := validateBMCBase(tt.raw)
			if tt.wantErr != "" {
				if err == nil {
					t.Fatalf("期望拒绝 %q，实际通过并得到 %s", tt.raw, got)
				}
				if !strings.Contains(err.Error(), tt.wantErr) {
					t.Errorf("错误信息 %q 未包含期望片段 %q", err.Error(), tt.wantErr)
				}
				return
			}
			if err != nil {
				t.Fatalf("期望通过 %q，实际报错: %v", tt.raw, err)
			}
			if got != tt.want {
				t.Errorf("根URL = %s，期望 %s", got, tt.want)
			}
		})
	}
}

func TestValidateBMCBase_HostAllowlist(t *testing.T) {
	t.Setenv(envAllowInsecureBMC, "")
	t.Setenv(envBMCHostAllowlist, "10.0.0.0/8, bmc.example.com")

	if _, err := validateBMCBase("10.20.30.40"); err != nil {
		t.Errorf("网段内地址应放行: %v", err)
	}
	if _, err := validateBMCBase("bmc.example.com"); err != nil {
		t.Errorf("白名单主机名应放行: %v", err)
	}
	err := func() error {
		_, err := validateBMCBase("192.168.1.1")
		return err
	}()
	if err == nil {
		t.Error("白名单外地址应拒绝")
	} else if !strings.Contains(err.Error(), "白名单") {
		t.Errorf("错误信息 %q 未点明白名单", err)
	}
}
