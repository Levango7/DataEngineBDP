{{/*
Expand the name of the chart.
*/}}
{{- define "csi-juicefs.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully qualified app name.
*/}}
{{- define "csi-juicefs.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Chart labels.
*/}}
{{- define "csi-juicefs.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "csi-juicefs.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
{{- end -}}

{{/*
Selector labels.
*/}}
{{- define "csi-juicefs.selectorLabels" -}}
app.kubernetes.io/name: {{ include "csi-juicefs.name" . | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
{{- end -}}

{{/*
ConfigMap name.
*/}}
{{- define "csi-juicefs.configmapName" -}}
{{- printf "%s-config" (include "csi-juicefs.fullname" .) -}}
{{- end -}}

{{/*
Service name.
*/}}
{{- define "csi-juicefs.serviceName" -}}
{{- include "csi-juicefs.fullname" . -}}
{{- end -}}

{{/*
Service account name.
*/}}
{{- define "csi-juicefs.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "csi-juicefs.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
