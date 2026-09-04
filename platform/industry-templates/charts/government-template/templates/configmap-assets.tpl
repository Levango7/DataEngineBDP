{{- /*
Helm Chart 模板 - ConfigMap 挂载政务模板资产
用途：将 DDL/DAG/Dashboard/IoTDB配置/RBAC 资产打包为 ConfigMap，供导入 Job 挂载使用
*/ -}}
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ .Values.configMap.namePrefix }}-assets
  namespace: {{ .Values.namespace }}
  labels:
    app.kubernetes.io/name: government-template
    app.kubernetes.io/instance: {{ .Release.Name }}
    app.kubernetes.io/version: "1.0.0"
    app.kubernetes.io/managed-by: {{ .Release.Service }}
    shuqing.io/template: government-template
    shuqing.io/template-version: "1.0.0"
  annotations:
    shuqing.io/business-domains: "population,economic,livelihood,compliance"
    shuqing.io/tables: "28"
    shuqing.io/dags: "8"
    shuqing.io/dashboards: "4"
    shuqing.io/roles: "5"
    shuqing.io/compliance: "true"
    shuqing.io/data-classification: "L1,L2,L3,L4"
    shuqing.io/iotdb: "true"
data:
{{- range $path, $_ := .Files.Glob "ddl/**" }}
  {{ $path }}: |-
{{ $.Files.Get $path | indent 4 }}
{{- end }}
{{- range $path, $_ := .Files.Glob "dag/**" }}
  {{ $path }}: |-
{{ $.Files.Get $path | indent 4 }}
{{- end }}
{{- range $path, $_ := .Files.Glob "dashboards/**" }}
  {{ $path }}: |-
{{ $.Files.Get $path | indent 4 }}
{{- end }}
{{- range $path, $_ := .Files.Glob "iotdb/**" }}
  {{ $path }}: |-
{{ $.Files.Get $path | indent 4 }}
{{- end }}
{{- range $path, $_ := .Files.Glob "rbac/**" }}
  {{ $path }}: |-
{{ $.Files.Get $path | indent 4 }}
{{- end }}