$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$base = "$repoRoot\deploy\k3s\manifests"

# 模块定义: name, image, port, type(Java/Python/Go)
$modules = @(
    @{Name="encaps-layer"; Image="sq/encaps-layer:0.1.0"; Port=8080; Type="Java"; HealthPath="/actuator/health"},
    @{Name="sql-gateway"; Image="sq/sql-gateway:0.1.0"; Port=8081; Type="Java"; HealthPath="/actuator/health"},
    @{Name="rule-engine"; Image="sq/rule-engine:0.1.0"; Port=8083; Type="Java"; HealthPath="/actuator/health"},
    @{Name="tag-engine"; Image="sq/tag-engine:0.1.0"; Port=8080; Type="Java"; HealthPath="/actuator/health"},
    @{Name="infra-orchestrator"; Image="sq/infra-orchestrator:0.1.0"; Port=8085; Type="Java"; HealthPath="/actuator/health"},
    @{Name="infra-provider-cloud"; Image="sq/infra-provider-cloud:0.1.0"; Port=8084; Type="Java"; HealthPath="/actuator/health"},
    @{Name="infra-provider-private"; Image="sq/infra-provider-private:0.1.0"; Port=8084; Type="Java"; HealthPath="/actuator/health"},
    @{Name="infra-provider-xinchang"; Image="sq/infra-provider-xinchang:0.1.0"; Port=8081; Type="Java"; HealthPath="/actuator/health"},
    @{Name="lineage-analyzer"; Image="sq/lineage-analyzer:0.1.0"; Port=8086; Type="Java"; HealthPath="/actuator/health"},
    @{Name="metadata-collector"; Image="sq/metadata-collector:0.1.0"; Port=8084; Type="Java"; HealthPath="/actuator/health"},
    @{Name="catalog"; Image="sq/catalog:0.1.0"; Port=8082; Type="Go"; HealthPath="/api/v1/health"},
    @{Name="llm-gateway"; Image="sq/llm-gateway:0.1.0"; Port=8084; Type="Go"; HealthPath="/health"},
    @{Name="vector-engine"; Image="sq/vector-engine:0.1.0"; Port=8086; Type="Go"; HealthPath="/health"},
    @{Name="infra-provider-baremetal"; Image="sq/infra-provider-baremetal:0.1.0"; Port=8080; Type="Go"; HealthPath="/health"},
    @{Name="asset-exchange"; Image="sq/asset-exchange:0.1.0"; Port=8087; Type="Python"; HealthPath="/health"},
    @{Name="business-portal"; Image="sq/business-portal:0.1.0"; Port=8088; Type="Python"; HealthPath="/health"},
    @{Name="industry-templates"; Image="sq/industry-templates:0.1.0"; Port=8091; Type="Python"; HealthPath="/health"},
    @{Name="knowledge-engine"; Image="sq/knowledge-engine:0.1.0"; Port=8080; Type="Python"; HealthPath="/health"},
    @{Name="llmops"; Image="sq/llmops:0.1.0"; Port=8080; Type="Python"; HealthPath="/health"},
    @{Name="ml-platform"; Image="sq/ml-platform:0.1.0"; Port=8080; Type="Python"; HealthPath="/health"},
    @{Name="open-api-catalog"; Image="sq/open-api-catalog:0.1.0"; Port=8090; Type="Python"; HealthPath="/health"},
    @{Name="nl2sql"; Image="sq/nl2sql:0.1.0"; Port=8093; Type="Python"; HealthPath="/api/v1/health"}
)

foreach ($m in $modules) {
    $name = $m.Name
    $image = $m.Image
    $port = $m.Port
    $healthPath = $m.HealthPath

    $yaml = @"
apiVersion: apps/v1
kind: Deployment
metadata:
  name: $name
  namespace: shuqing
  labels:
    app: $name
    app.kubernetes.io/part-of: shuqing-bigdata-platform
spec:
  replicas: 1
  selector:
    matchLabels:
      app: $name
  template:
    metadata:
      labels:
        app: $name
    spec:
      containers:
      - name: $name
        image: $image
        imagePullPolicy: IfNotPresent
        ports:
        - containerPort: $port
          name: http
        env:
        - name: SERVER_PORT
          value: "$port"
        readinessProbe:
          httpGet:
            path: $healthPath
            port: $port
          initialDelaySeconds: 15
          periodSeconds: 10
          failureThreshold: 6
        livenessProbe:
          httpGet:
            path: $healthPath
            port: $port
          initialDelaySeconds: 30
          periodSeconds: 20
          failureThreshold: 3
        resources:
          requests:
            cpu: 100m
            memory: 256Mi
          limits:
            cpu: 1000m
            memory: 1Gi
---
apiVersion: v1
kind: Service
metadata:
  name: $name
  namespace: shuqing
  labels:
    app: $name
    app.kubernetes.io/part-of: shuqing-bigdata-platform
spec:
  type: ClusterIP
  selector:
    app: $name
  ports:
  - name: http
    port: $port
    targetPort: $port
    protocol: TCP
"@

    $outFile = Join-Path $base "$name.yaml"
    $yaml | Out-File -FilePath $outFile -Encoding utf8 -NoNewline
    Write-Output "Generated: $name.yaml (port=$port)"
}

Write-Output "Total: $($modules.Count) modules"