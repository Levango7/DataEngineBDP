{{- /*
Helm Chart 模板 - ConfigMap 挂载零售模板资产
用途：将 DDL/DAG/Dashboard/RBAC/tag-engine-config 资产打包为 ConfigMap，供导入 Job 挂载使用
*/ -}}
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ .Values.configMap.namePrefix }}-assets
  namespace: {{ .Values.namespace }}
  labels:
    app.kubernetes.io/name: retail-template
    app.kubernetes.io/instance: {{ .Release.Name }}
    app.kubernetes.io/version: "1.0.0"
    app.kubernetes.io/managed-by: {{ .Release.Service }}
    shuqing.io/template: retail-template
    shuqing.io/template-version: "1.0.0"
  annotations:
    shuqing.io/business-domains: "product_profile,member_analysis,marketing_effect"
    shuqing.io/tables: "18"
    shuqing.io/dags: "6"
    shuqing.io/dashboards: "3"
    shuqing.io/roles: "3"
    shuqing.io/tag-engine: "enabled"
data:
{{- range $path, $_ := .Files.Glob "ddl/**" }}
  {{ $path }}: |-
{{ .Files.Get $path | indent 4 }}
{{- end }}
{{- range $path, $_ := .Files.Glob "dag/**" }}
  {{ $path }}: |-
{{ .Files.Get $path | indent 4 }}
{{- end }}
{{- range $path, $_ := .Files.Glob "dashboards/**" }}
  {{ $path }}: |-
{{ .Files.Get $path | indent 4 }}
{{- end }}
{{- range $path, $_ := .Files.Glob "tag-engine/**" }}
  {{ $path }}: |-
{{ .Files.Get $path | indent 4 }}
{{- end }}
  README.md: |-
{{ .Files.Get "README.md" | indent 4 }}