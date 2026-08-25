// Package k3sclient 提供轻量 k3s API 客户端（kubeconfig + HTTP，无 client-go 依赖）。
package k3sclient

import (
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"

	"gopkg.in/yaml.v3"
)

// KubeConfig kubeconfig 结构（最小字段集）。
type KubeConfig struct {
	Clusters []struct {
		Cluster struct {
			Server                   string `yaml:"server"`
			CertificateAuthorityData string `yaml:"certificate-authority-data"`
			InsecureSkipTLSVerify    bool   `yaml:"insecure-skip-tls-verify"`
		} `yaml:"cluster"`
	} `yaml:"clusters"`
	Users []struct {
		User struct {
			Token                 string `yaml:"token"`
			ClientCertificateData string `yaml:"client-certificate-data"`
			ClientKeyData         string `yaml:"client-key-data"`
		} `yaml:"user"`
	} `yaml:"users"`
	Contexts []struct {
		Context struct {
			Cluster string `yaml:"cluster"`
			User    string `yaml:"user"`
		} `yaml:"context"`
	} `yaml:"contexts"`
	CurrentContext string `yaml:"current-context"`
}

// Client 轻量 K8s REST 客户端。
type Client struct {
	server string
	token  string
	http   *http.Client
}

// NewFromKubeconfig 从 kubeconfig 路径创建客户端。
// 环境变量 K3S_KUBECONFIG 指定路径；默认尝试常见位置。
func NewFromKubeconfig(path string) (*Client, error) {
	if path == "" {
		path = os.Getenv("K3S_KUBECONFIG")
	}
	if path == "" {
		// 常见位置：Windows WSL 挂载 / Windows 本地 / 容器内
		for _, p := range []string{
			"/etc/rancher/k3s/k3s.yaml",
			"\\\\wsl$\\Ubuntu-24.04\\etc\\rancher\\k3s\\k3s.yaml",
			"C:/Users/winge/.kube/config",
		} {
			if _, err := os.Stat(p); err == nil {
				path = p
				break
			}
		}
	}
	if path == "" {
		return nil, fmt.Errorf("未找到 kubeconfig（设置 K3S_KUBECONFIG 或放于常见位置）")
	}

	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("读取 kubeconfig 失败: %w", err)
	}
	var cfg KubeConfig
	if err := yaml.Unmarshal(data, &cfg); err != nil {
		return nil, fmt.Errorf("解析 kubeconfig 失败: %w", err)
	}
	if len(cfg.Clusters) == 0 || len(cfg.Users) == 0 {
		return nil, fmt.Errorf("kubeconfig 缺少 cluster/user")
	}

	server := cfg.Clusters[0].Cluster.Server
	token := cfg.Users[0].User.Token
	// #nosec G402 -- InsecureSkipTLSVerify 来自 kubeconfig 显式配置，
	// 属部署方自主选择（内网 k3s 自签场景），非代码层默认跳过。
	tlsConf := &tls.Config{InsecureSkipVerify: cfg.Clusters[0].Cluster.InsecureSkipTLSVerify}
	// 加载 CA（kubeconfig certificate-authority-data，base64 编码 PEM）
	if caB64 := cfg.Clusters[0].Cluster.CertificateAuthorityData; caB64 != "" {
		caPEM, err := base64.StdEncoding.DecodeString(caB64)
		if err != nil {
			return nil, fmt.Errorf("解码 CA 失败: %w", err)
		}
		if pool, err := x509.SystemCertPool(); err == nil {
			if pool.AppendCertsFromPEM(caPEM) {
				tlsConf.RootCAs = pool
				tlsConf.InsecureSkipVerify = false
			}
		}
	}
	// mTLS：k3s kubeconfig 用 client-cert/key（base64 编码 PEM，需先解码）
	if certB64 := cfg.Users[0].User.ClientCertificateData; certB64 != "" {
		certPEM, err1 := base64.StdEncoding.DecodeString(certB64)
		if err1 != nil {
			return nil, fmt.Errorf("解码客户端证书失败: %w", err1)
		}
		keyPEM, err2 := base64.StdEncoding.DecodeString(cfg.Users[0].User.ClientKeyData)
		if err2 != nil {
			return nil, fmt.Errorf("解码客户端密钥失败: %w", err2)
		}
		cert, err := tls.X509KeyPair(certPEM, keyPEM)
		if err != nil {
			return nil, fmt.Errorf("加载客户端证书失败: %w", err)
		}
		tlsConf.Certificates = []tls.Certificate{cert}
	}
	transport := &http.Transport{TLSClientConfig: tlsConf}
	return &Client{
		server: server,
		token:  token,
		http:   &http.Client{Transport: transport, Timeout: 10 * time.Second},
	}, nil
}

// get 发起 K8s REST GET。
func (c *Client) get(path string, out interface{}) error {
	req, err := http.NewRequest(http.MethodGet, c.server+path, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	req.Header.Set("Accept", "application/json")

	resp, err := c.http.Do(req)
	if err != nil {
		return fmt.Errorf("k8s API 请求失败: %w", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	if resp.StatusCode >= 400 {
		return fmt.Errorf("k8s API 返回 %d: %s", resp.StatusCode, string(body))
	}
	return json.Unmarshal(body, out)
}

// NodeItem K8s Node 精简字段。
type NodeItem struct {
	Metadata struct {
		Name              string            `json:"name"`
		Labels            map[string]string `json:"labels"`
		CreationTimestamp string            `json:"creationTimestamp"`
	} `json:"metadata"`
	Status struct {
		Conditions []struct {
			Type   string `json:"type"`
			Status string `json:"status"`
		} `json:"conditions"`
		Capacity struct {
			CPU    string `json:"cpu"`
			Memory string `json:"memory"`
			Pods   string `json:"pods"`
		} `json:"capacity"`
		Allocatable struct {
			CPU    string `json:"cpu"`
			Memory string `json:"memory"`
		} `json:"allocatable"`
		NodeInfo struct {
			OSImage          string `json:"osImage"`
			ContainerRuntime string `json:"containerRuntimeVersion"`
		} `json:"nodeInfo"`
	} `json:"status"`
}

// NodeList K8s NodeList。
type NodeList struct {
	Items []NodeItem `json:"items"`
}

// PodItem K8s Pod 精简字段。
type PodItem struct {
	Metadata struct {
		Name            string            `json:"name"`
		Namespace       string            `json:"namespace"`
		Labels          map[string]string `json:"labels"`
		OwnerReferences []struct {
			Kind string `json:"kind"`
			Name string `json:"name"`
		} `json:"ownerReferences"`
		CreationTimestamp string `json:"creationTimestamp"`
	} `json:"metadata"`
	Spec struct {
		NodeName   string `json:"nodeName"`
		Containers []struct {
			Resources struct {
				Requests struct {
					CPU    string `json:"cpu"`
					Memory string `json:"memory"`
				} `json:"requests"`
			} `json:"resources"`
		} `json:"containers"`
	} `json:"spec"`
	Status struct {
		Phase      string `json:"phase"`
		StartTime  string `json:"startTime"`
		Conditions []struct {
			Type   string `json:"type"`
			Status string `json:"status"`
		} `json:"conditions"`
		ContainerStatuses []struct {
			RestartCount int `json:"restartCount"`
		} `json:"containerStatuses"`
	} `json:"status"`
}

// PodList K8s PodList。
type PodList struct {
	Items []PodItem `json:"items"`
}

// ListNodes 列出节点。
func (c *Client) ListNodes() (*NodeList, error) {
	var out NodeList
	if err := c.get("/api/v1/nodes", &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// ListPods 列出 Pod（全命名空间）。
func (c *Client) ListPods() (*PodList, error) {
	var out PodList
	if err := c.get("/api/v1/pods", &out); err != nil {
		return nil, err
	}
	return &out, nil
}
