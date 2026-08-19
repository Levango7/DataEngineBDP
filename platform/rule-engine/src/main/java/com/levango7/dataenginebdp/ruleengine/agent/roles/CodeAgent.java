package com.levango7.dataenginebdp.ruleengine.agent.roles;

import com.levango7.dataenginebdp.ruleengine.agent.core.Agent;
import com.levango7.dataenginebdp.ruleengine.agent.core.AgentContext;
import com.levango7.dataenginebdp.ruleengine.agent.core.AgentResult;
import com.levango7.dataenginebdp.ruleengine.agent.core.BaseAgent;
import com.levango7.dataenginebdp.ruleengine.agent.quota.QuotaEnforcer;
import com.levango7.dataenginebdp.ruleengine.agent.tool.ToolRegistry;
import com.levango7.dataenginebdp.ruleengine.agent.tool.ToolSandbox;
import com.levango7.dataenginebdp.ruleengine.agent.tool.ToolWhitelist;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 代码 Agent：数据管道代码生成。
 *
 * <p>根据需求生成数据管道代码（Spark/Flink/Python/SQL 等）。
 * 优先调用 {@code generate_code} 工具，未注册时回退到内置模板。</p>
 *
 * <p>输出 payload：
 * <ul>
 *   <li>{@code language}：代码语言</li>
 *   <li>{@code framework}：框架（spark/flink/python）</li>
 *   <li>{@code code}：生成的代码</li>
 *   <li>{@code files}：多文件结构（若生成多个文件）</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@Component
public class CodeAgent extends BaseAgent {

    private static final Logger LOG = Logger.getLogger(CodeAgent.class.getName());
    private static final String TOOL_GENERATE_CODE = "generate_code";
    /** 模板文件在 classpath 下的目录前缀 */
    private static final String TEMPLATE_DIR = "/templates/codeagent/";

    private final ToolSandbox sandbox;
    private final ToolRegistry toolRegistry;

    public CodeAgent(QuotaEnforcer quotaEnforcer, ToolWhitelist toolWhitelist,
                     ToolSandbox sandbox, ToolRegistry toolRegistry) {
        super(quotaEnforcer, toolWhitelist);
        this.sandbox = sandbox;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public Agent.Role getRole() {
        return Agent.Role.CODE;
    }

    @Override
    public AgentResult doExecute(AgentContext context) {
        String requirement = context.getUserInput();
        if (requirement == null || requirement.isBlank()) {
            Object obj = context.getInput("requirement");
            requirement = obj == null ? null : String.valueOf(obj);
        }
        if (requirement == null || requirement.isBlank()) {
            return AgentResult.failure(getRole(), AgentResult.Status.INVALID_INPUT,
                    "MISSING_REQUIREMENT", "requirement or userInput must not be blank",
                    0L, context.getTenantId(), context.getRequestId());
        }

        String language = context.getAttribute("language", String.class);
        if (language == null) {
            language = String.valueOf(context.getInput().getOrDefault("language", "python"));
        }
        String framework = context.getAttribute("framework", String.class);
        if (framework == null) {
            framework = String.valueOf(context.getInput().getOrDefault("framework", "pyspark"));
        }

        List<Map<String, Object>> toolCalls = new ArrayList<>();

        // 优先调用工具
        if (toolRegistry.contains(TOOL_GENERATE_CODE)) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("requirement", requirement);
            args.put("language", language);
            args.put("framework", framework);
            ToolSandbox.ToolInvocation inv = sandbox.invoke(toolRegistry, TOOL_GENERATE_CODE, args);
            toolCalls.add(toolCallRecord(TOOL_GENERATE_CODE, args));
            if (inv.success() && inv.result() instanceof Map<?, ?> resultMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = new LinkedHashMap<>((Map<String, Object>) resultMap);
                return AgentResult.success(getRole(), output,
                        List.of("pipeline-" + System.currentTimeMillis()), toolCalls,
                        null, context.getTenantId(), context.getRequestId());
            }
        }

        // 回退：内置模板
        String code = buildCode(requirement, language, framework);
        List<Map<String, String>> files = List.of(Map.of(
                "name", "pipeline." + extensionOf(language),
                "content", code
        ));

        Map<String, Object> output = output(
                "language", language,
                "framework", framework,
                "code", code,
                "files", files,
                "source", "builtin"
        );
        return AgentResult.success(getRole(), output,
                artifacts(code), toolCalls,
                null, context.getTenantId(), context.getRequestId());
    }

    private String buildCode(String requirement, String language, String framework) {
        return switch (language.toLowerCase()) {
            case "python", "py" -> buildPython(requirement, framework);
            case "scala" -> buildScala(requirement, framework);
            case "sql" -> buildSql(requirement);
            default -> buildDefault(requirement, language);
        };
    }

    private String buildPython(String requirement, String framework) {
        if (framework.toLowerCase().contains("flink")) {
            // 优先加载外部模板，失败时回退到内联模板
            String code = loadTemplate("pyflink.py.tmpl", requirement);
            if (code != null) {
                return code;
            }
            return "# PyFlink 数据管道\n"
                    + "# 需求: " + requirement + "\n\n"
                    + "from pyflink.datastream import StreamExecutionEnvironment\n\n"
                    + "env = StreamExecutionEnvironment.get_execution_environment()\n"
                    + "ds = env.from_elements([])\n"
                    + "ds.map(lambda x: x).print()\n"
                    + "env.execute('pipeline')\n";
        }
        // 优先加载外部模板，失败时回退到内联模板
        String code = loadTemplate("pyspark.py.tmpl", requirement);
        if (code != null) {
            return code;
        }
        return "# PySpark 数据管道\n"
                + "# 需求: " + requirement + "\n\n"
                + "from pyspark.sql import SparkSession\n\n"
                + "spark = SparkSession.builder.appName('pipeline').getOrCreate()\n"
                + "df = spark.read.parquet('s3://bucket/source')\n"
                + "df.filter(df['amount'] > 0).write.parquet('s3://bucket/target')\n"
                + "spark.stop()\n";
    }

    private String buildScala(String requirement, String framework) {
        // 优先加载外部模板，失败时回退到内联模板
        String code = loadTemplate("spark.scala.tmpl", requirement);
        if (code != null) {
            return code;
        }
        return "// Spark Scala 数据管道\n"
                + "// 需求: " + requirement + "\n\n"
                + "import org.apache.spark.sql.SparkSession\n\n"
                + "val spark = SparkSession.builder().appName(\"pipeline\").getOrCreate()\n"
                + "val df = spark.read.parquet(\"s3://bucket/source\")\n"
                + "df.filter(df(\"amount\") > 0).write.parquet(\"s3://bucket/target\")\n"
                + "spark.stop()\n";
    }

    private String buildSql(String requirement) {
        // 优先加载外部模板，失败时回退到内联模板
        String code = loadTemplate("pipeline.sql.tmpl", requirement);
        if (code != null) {
            return code;
        }
        return "-- 数据管道 SQL\n-- 需求: " + requirement + "\n"
                + "INSERT OVERWRITE TABLE target_table\n"
                + "SELECT * FROM source_table;\n";
    }

    private String buildDefault(String requirement, String language) {
        // 默认模板额外包含 ${language} 占位符
        String template = loadTemplateRaw("default.txt.tmpl");
        if (template != null) {
            return template.replace("${requirement}", requirement)
                    .replace("${language}", language);
        }
        return "# 生成代码\n# 需求: " + requirement + "\n# 语言: " + language + "\n";
    }

    /**
     * 从 classpath 加载模板文件，并把 {@code ${requirement}} 占位符替换为实际需求值。
     * 加载失败时返回 {@code null}，由调用方回退到内联字符串。
     *
     * @param name        模板文件名（相对于 {@link #TEMPLATE_DIR}）
     * @param requirement 需求文本
     * @return 渲染后的模板内容；加载失败时返回 {@code null}
     */
    private String loadTemplate(String name, String requirement) {
        String template = loadTemplateRaw(name);
        if (template == null) {
            return null;
        }
        return template.replace("${requirement}", requirement);
    }

    /**
     * 从 classpath 读取模板文件原始内容。加载失败时返回 {@code null}。
     *
     * @param name 模板文件名（相对于 {@link #TEMPLATE_DIR}）
     * @return 模板原始内容；文件不存在或读取异常时返回 {@code null}
     */
    private String loadTemplateRaw(String name) {
        try (InputStream in = getClass().getResourceAsStream(TEMPLATE_DIR + name)) {
            if (in == null) {
                LOG.warning("模板文件不存在: " + name + "，将回退到内联模板");
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "加载模板文件失败: " + name + "，将回退到内联模板", e);
            return null;
        }
    }

    private String extensionOf(String language) {
        return switch (language.toLowerCase()) {
            case "python", "py" -> "py";
            case "scala" -> "scala";
            case "sql" -> "sql";
            default -> "txt";
        };
    }
}