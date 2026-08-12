{{- /*
Helm Chart 模板 - 模板导入 Job
用途：部署一个 Job，拉取 ConfigMap 中的模板资产，依次导入到 Doris/DolphinScheduler/Superset/Keycloak
*/ -}}
{{- if .Values.importJob.enabled }}
apiVersion: batch/v1
kind: Job
metadata:
  name: {{ .Values.configMap.namePrefix }}-import
  namespace: {{ .Values.namespace }}
  labels:
    app.kubernetes.io/name: finance-template
    app.kubernetes.io/instance: {{ .Release.Name }}
    app.kubernetes.io/version: "1.0.0"
    app.kubernetes.io/managed-by: {{ .Release.Service }}
    shuqing.io/template: finance-template
  annotations:
    "helm.sh/hook": post-install,post-upgrade
    "helm.sh/hook-weight": "0"
    "helm.sh/hook-delete-policy": before-hook-creation,hook-succeeded
spec:
  backoffLimit: {{ .Values.importJob.backoffLimit }}
  activeDeadlineSeconds: {{ .Values.importJob.activeDeadlineSeconds }}
  template:
    metadata:
      labels:
        app.kubernetes.io/name: finance-template
        app.kubernetes.io/instance: {{ .Release.Name }}
    spec:
      restartPolicy: OnFailure
      volumes:
        - name: template-assets
          configMap:
            name: {{ .Values.configMap.namePrefix }}-assets
      containers:
        - name: template-importer
          image: "{{ .Values.importJob.image.repository }}:{{ .Values.importJob.image.tag }}"
          imagePullPolicy: {{ .Values.importJob.image.pullPolicy }}
          volumeMounts:
            - name: template-assets
              mountPath: /templates
              readOnly: true
          env:
            - name: TEMPLATE_NAME
              value: {{ .Values.template.name | quote }}
            - name: TEMPLATE_VERSION
              value: {{ .Values.template.version | quote }}
            - name: DORIS_FE_HOST
              value: {{ .Values.target.doris.feHost | quote }}
            - name: DORIS_FE_PORT
              value: {{ .Values.target.doris.fePort | quote }}
            - name: DORIS_DATABASE
              value: {{ .Values.target.doris.database | quote }}
            - name: DORIS_USER
              value: {{ .Values.target.doris.user | quote }}
            - name: DORIS_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ .Values.target.doris.passwordSecret.name }}
                  key: {{ .Values.target.doris.passwordSecret.key }}
            - name: DS_HOST
              value: {{ .Values.target.dolphinscheduler.host | quote }}
            - name: DS_PORT
              value: {{ .Values.target.dolphinscheduler.port | quote }}
            - name: DS_TOKEN
              valueFrom:
                secretKeyRef:
                  name: {{ .Values.target.dolphinscheduler.tokenSecret.name }}
                  key: {{ .Values.target.dolphinscheduler.tokenSecret.key }}
            - name: SUPERSET_HOST
              value: {{ .Values.target.superset.host | quote }}
            - name: SUPERSET_PORT
              value: {{ .Values.target.superset.port | quote }}
            - name: SUPERSET_TOKEN
              valueFrom:
                secretKeyRef:
                  name: {{ .Values.target.superset.tokenSecret.name }}
                  key: {{ .Values.target.superset.tokenSecret.key }}
            - name: KEYCLOAK_HOST
              value: {{ .Values.target.keycloak.host | quote }}
            - name: KEYCLOAK_PORT
              value: {{ .Values.target.keycloak.port | quote }}
            - name: KEYCLOAK_REALM
              value: {{ .Values.target.keycloak.realm | quote }}
            - name: KEYCLOAK_ADMIN_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ .Values.target.keycloak.adminSecret.name }}
                  key: {{ .Values.target.keycloak.adminSecret.key }}
            - name: RBAC_SYNC_ENABLED
              value: {{ .Values.rbacSync.enabled | quote }}
            - name: RBAC_ROLLBACK_ON_FAILURE
              value: {{ .Values.rbacSync.rollbackOnFailure | quote }}
          command:
            - /bin/sh
            - -c
            - |
              set -e
              echo "=========================================="
              echo "金融行业模板导入开始"
              echo "模板: ${TEMPLATE_NAME} v${TEMPLATE_VERSION}"
              echo "=========================================="
              echo "[1/4] 导入 DDL 到 Doris..."
              for sql_file in /templates/ddl/*.sql; do
                echo "  执行: $(basename $sql_file)"
                mysql -h ${DORIS_FE_HOST} -P ${DORIS_FE_PORT} -u ${DORIS_USER} -p${DORIS_PASSWORD} ${DORIS_DATABASE} < $sql_file
              done
              echo "[1/4] DDL 导入完成"
              echo "[2/4] 导入 DAG 到 DolphinScheduler..."
              for dag_file in /templates/dag/*.json; do
                echo "  导入: $(basename $dag_file)"
                curl -X POST "http://${DS_HOST}:${DS_PORT}/dolphinscheduler/projects/import" \
                  -H "Authorization: Bearer ${DS_TOKEN}" \
                  -F "file=@${dag_file}"
              done
              echo "[2/4] DAG 导入完成"
              echo "[3/4] 导入 Dashboard 到 Superset..."
              for dash_file in /templates/dashboard/*.json; do
                echo "  导入: $(basename $dash_file)"
                curl -X POST "http://${SUPERSET_HOST}:${SUPERSET_PORT}/api/v1/dashboard/import/" \
                  -H "Authorization: Bearer ${SUPERSET_TOKEN}" \
                  -F "formData=@${dash_file}"
              done
              echo "[3/4] Dashboard 导入完成"
              if [ "${RBAC_SYNC_ENABLED}" = "true" ]; then
                echo "[4/4] 导入 RBAC 到 Keycloak..."
                KC_ADMIN_TOKEN=$(curl -s -X POST "http://${KEYCLOAK_HOST}:${KEYCLOAK_PORT}/realms/master/protocol/openid-connect/token" \
                  -d "client_id=admin-cli" \
                  -d "username=admin" \
                  -d "password=${KEYCLOAK_ADMIN_PASSWORD}" \
                  -d "grant_type=password" | jq -r .access_token)
                curl -X POST "http://${KEYCLOAK_HOST}:${KEYCLOAK_PORT}/admin/realms" \
                  -H "Authorization: Bearer ${KC_ADMIN_TOKEN}" \
                  -H "Content-Type: application/json" \
                  -d "{\"realm\":\"${KEYCLOAK_REALM}\",\"enabled\":true}"
                echo "[4/4] RBAC 导入完成"
              else
                echo "[4/4] RBAC 同步已禁用，跳过"
              fi
              echo "=========================================="
              echo "金融行业模板导入完成"
              echo "=========================================="
          resources:
            {{- toYaml .Values.importJob.resources | nindent 12 }}
{{- end }}