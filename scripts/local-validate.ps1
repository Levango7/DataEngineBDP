# v2.1.0-RC 本地验证 PowerShell 封装（Windows + WSL2 友好）
# 用法：
#   .\scripts\local-validate.ps1              # 仅 Profile 渲染校验（无需 kind）
#   .\scripts\local-validate.ps1 -Full       # 完整 kind 部署 + 冒烟测试（需 WSL2 + Docker Desktop）
#   .\scripts\local-validate.ps1 -Cleanup    # 清理 kind 集群

param(
    [switch]$Full,
    [switch]$Cleanup,
    [string]$ClusterName = "dataengine-local",
    [string]$Namespace = "dataengine"
)

function Log { param($msg) Write-Host "[$(Get-Date -Format HH:mm:ss)] $msg" -ForegroundColor Cyan }
function Fail { param($msg) Write-Host "FAIL: $msg" -ForegroundColor Red; exit 1 }
function Pass { param($msg) Write-Host "PASS: $msg" -ForegroundColor Green }

# 检查前置工具
function Check-Tools {
    $tools = @("docker", "kind", "kubectl", "helm")
    foreach ($t in $tools) {
        if (-not (Get-Command $t -ErrorAction SilentlyContinue)) {
            Fail "缺少 $t，请先安装（建议在 WSL2 中安装）"
        }
    }
    Pass "前置工具检查通过"
}

# 仅 Profile 渲染校验
function Test-Profiles {
    Log "=== Profile 渲染校验 ==="
    $envs = @("xinchuang", "onprem", "public-cloud", "private-cloud")
    $umbrella = "design/deploy/charts/dataenginebdp-umbrella"
    $values = "deploy/local/values-local-core.yaml"

    helm dependency update $umbrella > $null

    foreach ($env in $envs) {
        Log "Profile: $env"
        $out = helm template "test-core" $umbrella -n smoke -f $values --set "global.env=$env" 2>&1
        if ($LASTEXITCODE -ne 0) { Fail "渲染失败 ($env): $out" }
        $out > /tmp/render.yaml
        kubeconform -strict -ignore-missing-schemas -kubernetes-version 1.29.0 -summary /tmp/render.yaml 2>$null
        if ($LASTEXITCODE -ne 0) { Fail "Schema 校验失败 ($env)" }
        if ($out -match "REPLACE_WITH_|CHANGE_ME_|your-|<.*>") {
            Fail "发现占位符 ($env)"
        }
        Pass "Profile $env 通过"
    }
    Pass "四环境 Profile 渲染校验全部通过"
}

# 完整 kind 部署 + 冒烟测试
function Deploy-Kind {
    Log "=== 完整 kind 部署 ==="

    # 1. 创建/复用集群
    if (kind get clusters | Select-String -Pattern "^$ClusterName$") {
        Log "集群 $ClusterName 已存在，复用"
    } else {
        Log "创建 kind 集群 $ClusterName..."
        kind create cluster --name $ClusterName --wait 300s
    }
    kubectl config use-context "kind-$ClusterName"

    # 2. 命名空间
    if (-not (kubectl get ns $Namespace -ErrorAction SilentlyContinue)) {
        kubectl create namespace $Namespace
    }

    # 3. 安装核心子集
    Log "安装核心子集..."
    helm upgrade --install dataengine design/deploy/charts/dataenginebdp-umbrella `
        -n $Namespace -f deploy/local/values-local-core.yaml --wait --timeout 15m

    # 4. 冒烟测试
    Log "运行冒烟测试..."
    bash scripts/smoke-test.sh
}

# 清理
function Cleanup-Cluster {
    Log "清理 kind 集群 $ClusterName..."
    kind delete cluster --name $ClusterName
    Pass "清理完成"
}

# 主流程
Check-Tools

if ($Cleanup) { Cleanup-Cluster; exit 0 }
if (-not $Full) { Test-Profiles; exit 0 }

Deploy-Kind
Pass "=== v2.1.0-RC 本地验证全部通过 ==="