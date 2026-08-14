package com.levango7.dataenginebdp.streambatch.flink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.Map;

/**
 * Flink REST API 客户端（真实提交路径）。
 *
 * <p>实现 Flink 标准提交流程：
 * <ol>
 *   <li>{@code POST /jars/upload} 上传作业 jar，获取 jarId</li>
 *   <li>{@code POST /jars/{jarId}/run} 提交运行，响应含真实 jobId</li>
 *   <li>{@code PATCH /jobs/{jobId}/cancel} 取消作业</li>
 * </ol>
 *
 * <p>由 {@link FlinkStreamSubmitter} 在 {@code realSubmitEnabled=true} 时调用；
 * 集群不可达时抛异常（调用方回退或报错），不静默模拟。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlinkRestClient {

    private final FlinkStreamConfig flinkConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * 上传 jar 到 Flink JobManager。
     *
     * @param jarPath 本地 jar 文件路径
     * @return 上传后返回的 jarId
     * @throws IOException jar 不存在或读取失败
     */
    public String uploadJar(String jarPath) throws IOException {
        if (jarPath == null || jarPath.isBlank()) {
            throw new IllegalArgumentException("jarPath 不能为空（realSubmitEnabled=true 需要作业 jar）");
        }
        java.io.File jar = new java.io.File(jarPath);
        if (!jar.exists()) {
            throw new IOException("jar 不存在: " + jarPath);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        org.springframework.core.io.FileSystemResource resource = new org.springframework.core.io.FileSystemResource(jar);
        org.springframework.http.HttpEntity<Object> entity = new org.springframework.http.HttpEntity<>(resource, headers);

        String url = flinkConfig.getJobManagerRest() + "/jars/upload";
        try {
            ResponseEntity<String> resp = restTemplate().postForEntity(url, entity, String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new IOException("Flink jar 上传失败: HTTP " + resp.getStatusCode());
            }
            JsonNode root = objectMapper.readTree(resp.getBody());
            String filename = root.path("filename").asText();
            // filename 形如 /flink/upload/jars/uuid_xxx.jar，取 jarId
            if (filename.isEmpty()) {
                throw new IOException("Flink jar 上传响应缺少 filename");
            }
            String jarId = new java.io.File(filename).getName();
            log.info("Flink jar 上传成功: jarId={}, size={}B", jarId, jar.length());
            return jarId;
        } catch (RestClientException e) {
            throw new IOException("Flink REST 不可达: " + flinkConfig.getJobManagerRest(), e);
        }
    }

    /**
     * 提交已上传 jar 运行。
     *
     * @param jarId     上传返回的 jarId
     * @param entryClass 作业入口类
     * @param programArgs 作业参数（可空）
     * @param parallelism 并行度
     * @param flinkConf  Flink 配置（Iceberg connector 等）
     * @return 真实 Flink jobId
     * @throws IOException 提交失败
     */
    public String runJar(String jarId, String entryClass, String programArgs,
                         int parallelism, Map<String, String> flinkConf) throws IOException {
        StringBuilder payload = new StringBuilder("{");
        if (entryClass != null && !entryClass.isEmpty()) {
            payload.append("\"entryClass\":\"").append(entryClass).append("\",");
        }
        payload.append("\"parallelism\":").append(parallelism);
        if (programArgs != null && !programArgs.isEmpty()) {
            payload.append(",\"programArgs\":\"").append(programArgs).append("\"");
        }
        if (flinkConf != null && !flinkConf.isEmpty()) {
            StringBuilder confJson = new StringBuilder();
            flinkConf.forEach((k, v) -> {
                if (confJson.length() > 0) {
                    confJson.append(",");
                }
                confJson.append("\"").append(k).append("\":\"").append(v).append("\"");
            });
            payload.append(",\"flinkConfiguration\":{").append(confJson).append("}");
        }
        payload.append("}");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(payload.toString(), headers);

        String url = flinkConfig.getJobManagerRest() + "/jars/" + jarId + "/run";
        try {
            ResponseEntity<String> resp = restTemplate().postForEntity(url, entity, String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new IOException("Flink 作业提交失败: HTTP " + resp.getStatusCode() + " body=" + resp.getBody());
            }
            JsonNode root = objectMapper.readTree(resp.getBody());
            String jobId = root.path("jobid").asText();
            if (jobId.isEmpty()) {
                throw new IOException("Flink 提交响应缺少 jobid: " + resp.getBody());
            }
            log.info("Flink 作业已提交: jobId={}", jobId);
            return jobId;
        } catch (RestClientException e) {
            throw new IOException("Flink REST 不可达: " + flinkConfig.getJobManagerRest(), e);
        }
    }

    /**
     * 取消作业（真实 REST PATCH）。
     */
    public void cancel(String jobId) {
        try {
            restTemplate().patchForObject(flinkConfig.getJobManagerRest() + "/jobs/" + jobId + "/cancel",
                    null, String.class);
            log.info("Flink 作业已取消: jobId={}", jobId);
        } catch (RestClientException e) {
            log.warn("Flink 取消失败 jobId={}: {}", jobId, e.getMessage());
        }
    }
}
