// Package service - baremetal_service_test.go 供应服务单元测试。
package service

import (
	"context"
	"testing"

	"github.com/sirupsen/logrus"

	"github.com/Levango7/DataEngineBDP/infra-provider-baremetal/src/internal/model"
)

// newTestService 创建测试用供应服务(内存SQLite)
func newTestService(t *testing.T) *BareMetalService {
	t.Helper()
	db, err := InitDatabase("sqlite", ":memory:", 5, 2)
	if err != nil {
		t.Fatalf("初始化测试DB失败: %v", err)
	}

	logger := logrus.NewEntry(logrus.New())
	redfish := NewRedfishClient(0, true, "admin", "admin")
	k8s := NewK8sBootstrapper(&mockExecutor{}, "registry.k8s.io", "10.244.0.0/16", "10.96.0.0/12", 6443)

	svc := NewBareMetalService(db, redfish, k8s, logger)
	if err := svc.AutoMigrate(); err != nil {
		t.Fatalf("AutoMigrate失败: %v", err)
	}
	return svc
}

// mockExecutor 测试用命令执行器
type mockExecutor struct{}

func (m *mockExecutor) Execute(ctx context.Context, host, command string) (string, string, int, error) {
	return "kubeadm join 127.0.0.1:6443 --token dev --discovery-token-ca-cert-hash sha256:hash\n", "", 0, nil
}

func (m *mockExecutor) CopyFile(ctx context.Context, host, localPath, remotePath string) error {
	return nil
}

func TestBareMetalService_CreateCluster_Validation(t *testing.T) {
	svc := newTestService(t)

	tests := []struct {
		name string
		req  *model.CreateClusterRequest
	}{
		{
			name: "空名称",
			req: &model.CreateClusterRequest{
				Name: "",
				K8s:  model.K8sConfig{KubernetesVersion: "v1.29.2", PodCIDR: "10.244.0.0/16", ServiceCIDR: "10.96.0.0/12", APIServerPort: 6443},
				Nodes: []model.NodeSpec{
					{Hostname: "cp1", Role: model.NodeRoleControlPlane, BMC: model.BMCCredential{Host: "10.0.0.1"}, ManagementIP: "10.0.0.11"},
				},
			},
		},
		{
			name: "无节点",
			req: &model.CreateClusterRequest{
				Name:  "test",
				K8s:   model.K8sConfig{KubernetesVersion: "v1.29.2", PodCIDR: "10.244.0.0/16", ServiceCIDR: "10.96.0.0/12", APIServerPort: 6443},
				Nodes: []model.NodeSpec{},
			},
		},
		{
			name: "无控制平面",
			req: &model.CreateClusterRequest{
				Name: "test",
				K8s:  model.K8sConfig{KubernetesVersion: "v1.29.2", PodCIDR: "10.244.0.0/16", ServiceCIDR: "10.96.0.0/12", APIServerPort: 6443},
				Nodes: []model.NodeSpec{
					{Hostname: "w1", Role: model.NodeRoleWorker, BMC: model.BMCCredential{Host: "10.0.0.1"}, ManagementIP: "10.0.0.11"},
				},
			},
		},
		{
			name: "缺少BMC",
			req: &model.CreateClusterRequest{
				Name: "test",
				K8s:  model.K8sConfig{KubernetesVersion: "v1.29.2", PodCIDR: "10.244.0.0/16", ServiceCIDR: "10.96.0.0/12", APIServerPort: 6443},
				Nodes: []model.NodeSpec{
					{Hostname: "cp1", Role: model.NodeRoleControlPlane, ManagementIP: "10.0.0.11"},
				},
			},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := svc.CreateCluster(context.Background(), tt.req)
			if err == nil {
				t.Errorf("期望返回错误，但CreateCluster成功")
			}
		})
	}
}

func TestBareMetalService_CreateCluster_Success(t *testing.T) {
	svc := newTestService(t)

	req := &model.CreateClusterRequest{
		Name: "test-cluster",
		K8s:  model.K8sConfig{KubernetesVersion: "v1.29.2", PodCIDR: "10.244.0.0/16", ServiceCIDR: "10.96.0.0/12", APIServerPort: 6443},
		Nodes: []model.NodeSpec{
			{Hostname: "cp1", Role: model.NodeRoleControlPlane, BMC: model.BMCCredential{Host: "10.0.0.1"}, ManagementIP: "10.0.0.11"},
			{Hostname: "w1", Role: model.NodeRoleWorker, BMC: model.BMCCredential{Host: "10.0.0.2"}, ManagementIP: "10.0.0.12"},
		},
	}

	cluster, err := svc.CreateCluster(context.Background(), req)
	if err != nil {
		t.Fatalf("CreateCluster失败: %v", err)
	}
	if cluster.Name != "test-cluster" {
		t.Errorf("期望Name=test-cluster，得到 %s", cluster.Name)
	}
	if cluster.ControlPlaneCount != 1 {
		t.Errorf("期望ControlPlaneCount=1，得到 %d", cluster.ControlPlaneCount)
	}
	if cluster.WorkerCount != 1 {
		t.Errorf("期望WorkerCount=1，得到 %d", cluster.WorkerCount)
	}
	if cluster.NodeCount != 2 {
		t.Errorf("期望NodeCount=2，得到 %d", cluster.NodeCount)
	}
}

func TestBareMetalService_GetCluster_NotFound(t *testing.T) {
	svc := newTestService(t)

	_, err := svc.GetCluster(context.Background(), "non-existent-id")
	if err == nil {
		t.Fatal("期望返回错误，但GetCluster成功")
	}
}

func TestBareMetalService_ListClusters(t *testing.T) {
	svc := newTestService(t)

	clusters, err := svc.ListClusters(context.Background())
	if err != nil {
		t.Fatalf("ListClusters失败: %v", err)
	}
	if len(clusters) != 0 {
		t.Errorf("期望空列表，得到 %d 个集群", len(clusters))
	}
}

func TestValidateCreateRequest(t *testing.T) {
	tests := []struct {
		name    string
		req     *model.CreateClusterRequest
		wantErr bool
	}{
		{
			name: "有效请求",
			req: &model.CreateClusterRequest{
				Name: "valid",
				K8s:  model.K8sConfig{KubernetesVersion: "v1.29.2", PodCIDR: "10.244.0.0/16", ServiceCIDR: "10.96.0.0/12", APIServerPort: 6443},
				Nodes: []model.NodeSpec{
					{Hostname: "cp1", Role: model.NodeRoleControlPlane, BMC: model.BMCCredential{Host: "10.0.0.1"}, ManagementIP: "10.0.0.11"},
				},
			},
			wantErr: false,
		},
		{
			name: "重复hostname",
			req: &model.CreateClusterRequest{
				Name: "dup",
				K8s:  model.K8sConfig{KubernetesVersion: "v1.29.2", PodCIDR: "10.244.0.0/16", ServiceCIDR: "10.96.0.0/12", APIServerPort: 6443},
				Nodes: []model.NodeSpec{
					{Hostname: "cp1", Role: model.NodeRoleControlPlane, BMC: model.BMCCredential{Host: "10.0.0.1"}, ManagementIP: "10.0.0.11"},
					{Hostname: "cp1", Role: model.NodeRoleWorker, BMC: model.BMCCredential{Host: "10.0.0.2"}, ManagementIP: "10.0.0.12"},
				},
			},
			wantErr: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := validateCreateRequest(tt.req)
			if (err != nil) != tt.wantErr {
				t.Errorf("validateCreateRequest错误=%v, wantErr=%v", err, tt.wantErr)
			}
		})
	}
}

func TestCountNodes(t *testing.T) {
	nodes := []model.NodeSpec{
		{Role: model.NodeRoleControlPlane},
		{Role: model.NodeRoleControlPlane},
		{Role: model.NodeRoleWorker},
	}
	cp, worker := countNodes(nodes)
	if cp != 2 {
		t.Errorf("期望cp=2，得到 %d", cp)
	}
	if worker != 1 {
		t.Errorf("期望worker=1，得到 %d", worker)
	}
}

func TestBuildNodeFromSpec(t *testing.T) {
	spec := model.NodeSpec{
		Hostname:     "node1",
		Role:         model.NodeRoleWorker,
		ManagementIP: "10.0.0.11",
		BMC:          model.BMCCredential{Host: "10.0.0.1", Username: "admin", Password: "pass"},
	}
	node := buildNodeFromSpec("cluster-1", spec)
	if node.ClusterID != "cluster-1" {
		t.Errorf("期望ClusterID=cluster-1，得到 %s", node.ClusterID)
	}
	if node.Hostname != "node1" {
		t.Errorf("期望Hostname=node1，得到 %s", node.Hostname)
	}
	if node.BMCHost != "10.0.0.1" {
		t.Errorf("期望BMCHost=10.0.0.1，得到 %s", node.BMCHost)
	}
	if node.OSImage == "" {
		t.Error("期望默认OSImage非空")
	}
}
