package k3sclient

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// NewFromKubeconfig 的兜底路径列表曾是本包的缺陷所在（含开发者个人机器路径），
// 且路径来自环境变量/入参后直接进 os.ReadFile。这三例锁住修完后的契约。

func TestNewFromKubeconfigRejectsParentTraversal(t *testing.T) {
	// 即使 ../ 指向的文件真的存在，也必须拒绝：配置里一个上级跳出
	// 不应让服务去读部署目录之外的任意文件。
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("USERPROFILE", home) // Windows 下 os.UserHomeDir 读这个
	t.Setenv("K3S_KUBECONFIG", "")

	outside := filepath.Join(filepath.Dir(home), "outside.yaml")
	if err := os.WriteFile(outside, []byte("clusters: []\n"), 0o600); err != nil {
		t.Fatalf("准备越界文件失败: %v", err)
	}
	rel := ".." + string(filepath.Separator) + filepath.Base(outside)

	_, err := NewFromKubeconfig(rel)
	if err == nil {
		t.Fatal("含上级跳出的路径必须被拒绝，却成功加载了")
	}
	if !strings.Contains(err.Error(), "不合法") {
		t.Fatalf("应报路径不合法，实际: %v", err)
	}
}

func TestNewFromKubeconfigErrorNamesTheEnvVar(t *testing.T) {
	// 空环境（既无入参也无默认位置命中）必须 fail-loud，且指出该设哪个变量；
	// 静默挑一个"看起来在"的路径会让排查者去查 k3s，而真因是没配 kubeconfig。
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("USERPROFILE", home)
	t.Setenv("K3S_KUBECONFIG", "")

	_, err := NewFromKubeconfig("")
	if err == nil {
		t.Fatal("无任何 kubeconfig 时应报错")
	}
	if !strings.Contains(err.Error(), "K3S_KUBECONFIG") {
		t.Fatalf("错误信息应指明环境变量名，实际: %v", err)
	}
}

func TestNewFromKubeconfigAcceptsExplicitPath(t *testing.T) {
	dir := t.TempDir()
	cfg := filepath.Join(dir, "kubeconfig.yaml")
	// 解析器要求至少一个 cluster 与一个 user（client.go 的 len()==0 校验），
	// 故用最小可用文档而不是 clusters: []。
	content := strings.Join([]string{
		"apiVersion: v1",
		"kind: Config",
		"clusters:",
		"- name: k3s",
		"  cluster:",
		"    server: https://127.0.0.1:6443",
		"    insecure-skip-tls-verify: true",
		"users:",
		"- name: admin",
		"  user:",
		"    token: fake-token",
		"contexts:",
		"- name: default",
		"  context:",
		"    cluster: k3s",
		"    user: admin",
		"current-context: default",
		"",
	}, "\n")
	if err := os.WriteFile(cfg, []byte(content), 0o600); err != nil {
		t.Fatalf("写入测试 kubeconfig 失败: %v", err)
	}

	if _, err := NewFromKubeconfig(cfg); err != nil {
		t.Fatalf("合法路径应被接受，实际报错: %v", err)
	}
}
