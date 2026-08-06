#!/usr/bin/env pwsh
<#
.SYNOPSIS
    数擎大数据平台 · Python 组件集成测试本地运行脚本（Windows PowerShell）

.DESCRIPTION
    本脚本用于在本地环境运行 5 个 Python 组件的集成测试：
      - asset-exchange      (port 8087)
      - business-portal     (port 8088)
      - open-api-catalog    (port 8090)
      - industry-templates  (port 8091)
      - knowledge-engine    (port 8080)

    脚本流程：
      1. 检查 Python 版本（要求 3.10+）
      2. 检查集成测试依赖（pytest / httpx / requests）
      3. 检查各 Python 组件依赖是否已安装（尝试 import 组件包）
      4. 运行 pytest 只执行 Python 组件测试文件
      5. 输出测试报告（HTML + 控制台摘要）

    组件由 conftest.py 中的 session 级 fixture 自动启动/停止，
    无需手动启动。若组件已外部启动，设置环境变量：
      $env:SQ_IT_SKIP_PYTHON_START=1

.PARAMETER TestFile
    指定单个测试文件运行（默认运行全部 5 个 Python 组件测试）。
    示例：.\run_python_tests.ps1 -TestFile test_asset_exchange.py

.PARAMETER Verbose
    输出详细日志。

.EXAMPLE
    .\run_python_tests.ps1
    .\run_python_tests.ps1 -TestFile test_knowledge_engine.py
    .\run_python_tests.ps1 -Verbose
#>
[CmdletBinding()]
param(
    [string]$TestFile = "",
    [switch]$VerboseOutput
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Resolve-Path "$PSScriptRoot\..\.."
$IntegrationDir = $PSScriptRoot

Write-Host "================================================" -ForegroundColor Cyan
Write-Host "  数擎大数据平台 · Python 组件集成测试" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""

# ---------------------------------------------------------------------------
# 1. 检查 Python 版本
# ---------------------------------------------------------------------------
Write-Host "[1/4] 检查 Python 环境..." -ForegroundColor Yellow
$pythonVersion = & python --version 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "  ✗ 未找到 Python，请先安装 Python 3.10+" -ForegroundColor Red
    exit 1
}
Write-Host "  ✓ $pythonVersion" -ForegroundColor Green

# ---------------------------------------------------------------------------
# 2. 检查集成测试依赖
# ---------------------------------------------------------------------------
Write-Host "[2/4] 检查集成测试依赖..." -ForegroundColor Yellow
$deps = @("pytest", "httpx", "requests")
foreach ($dep in $deps) {
    $check = & python -c "import $dep; print($dep.__version__)" 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  ✗ 缺少依赖: $dep，正在安装..." -ForegroundColor Yellow
        & pip install $dep
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  ✗ 安装 $dep 失败" -ForegroundColor Red
            exit 1
        }
    } else {
        Write-Host "  ✓ $dep ($check)" -ForegroundColor Green
    }
}

# ---------------------------------------------------------------------------
# 3. 检查 Python 组件依赖
# ---------------------------------------------------------------------------
Write-Host "[3/4] 检查 Python 组件依赖..." -ForegroundColor Yellow
$components = @(
    @{ Name = "asset-exchange";      Pkg = "asset_exchange";      Dir = "platform\asset-exchange" },
    @{ Name = "business-portal";     Pkg = "business_portal";     Dir = "platform\business-portal" },
    @{ Name = "open-api-catalog";    Pkg = "openapi_catalog";     Dir = "platform\open-api-catalog" },
    @{ Name = "industry-templates";  Pkg = "industry_templates";  Dir = "platform\industry-templates" },
    @{ Name = "knowledge-engine";    Pkg = "knowledge_engine";    Dir = "platform\knowledge-engine" }
)
foreach ($comp in $components) {
    $compDir = Join-Path $ProjectRoot $comp.Dir
    $check = & python -c "import sys; sys.path.insert(0, r'$compDir'); import $($comp.Pkg); print('ok')" 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  ⚠ $($comp.Name): 依赖未安装，尝试 pip install..." -ForegroundColor Yellow
        $reqFile = Join-Path $compDir "requirements.txt"
        if (Test-Path $reqFile) {
            & pip install -r $reqFile 2>&1 | Out-Null
        }
        # 重新检查
        $check = & python -c "import sys; sys.path.insert(0, r'$compDir'); import $($comp.Pkg); print('ok')" 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  ✗ $($comp.Name): 依赖安装失败，请手动检查" -ForegroundColor Red
        } else {
            Write-Host "  ✓ $($comp.Name)" -ForegroundColor Green
        }
    } else {
        Write-Host "  ✓ $($comp.Name)" -ForegroundColor Green
    }
}

# ---------------------------------------------------------------------------
# 4. 运行 pytest
# ---------------------------------------------------------------------------
Write-Host "[4/4] 运行集成测试..." -ForegroundColor Yellow
Write-Host ""

$testFiles = @(
    "test_asset_exchange.py",
    "test_business_portal.py",
    "test_open_api_catalog.py",
    "test_industry_templates.py",
    "test_knowledge_engine.py"
)

if ($TestFile -ne "") {
    $testFiles = @($TestFile)
}

$pytestArgs = @()
foreach ($f in $testFiles) {
    $pytestArgs += (Join-Path $IntegrationDir $f)
}
$pytestArgs += @(
    "-v",
    "--tb=short",
    "--html=$(Join-Path $IntegrationDir 'report_python.html')",
    "--self-contained-html"
)
if ($VerboseOutput) {
    $pytestArgs += "--log-cli-level=INFO"
}

Write-Host "pytest $($pytestArgs -join ' ')" -ForegroundColor DarkGray
Write-Host ""

& python -m pytest @pytestArgs
$exitCode = $LASTEXITCODE

Write-Host ""
if ($exitCode -eq 0) {
    Write-Host "================================================" -ForegroundColor Green
    Write-Host "  ✓ 所有测试通过" -ForegroundColor Green
    Write-Host "================================================" -ForegroundColor Green
} else {
    Write-Host "================================================" -ForegroundColor Red
    Write-Host "  ✗ 部分测试失败（退出码 $exitCode）" -ForegroundColor Red
    Write-Host "================================================" -ForegroundColor Red
}
Write-Host ""
Write-Host "HTML 报告: $(Join-Path $IntegrationDir 'report_python.html')" -ForegroundColor Cyan

exit $exitCode