package com.shuqing.bigdata.flinkcdc;

import com.shuqing.bigdata.flinkcdc.config.CdcYamlConfig;
import com.shuqing.bigdata.flinkcdc.sink.SinkConfig;
import com.shuqing.bigdata.flinkcdc.source.SourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Flink CDC 管道命令行入口，从 YAML 配置启动 CDC 作业。
 *
 * <p>用法：</p>
 * <pre>{@code
 * flink run -c com.shuqing.bigdata.flinkcdc.CdcFrameworkMain flink-cdc.jar --config cdc-mysql.yaml
 * }</pre>
 *
 * <p>支持参数：</p>
 * <ul>
 *   <li>{@code --config <path>}  YAML 配置文件路径（必填）</li>
 *   <li>{@code --async}          非阻塞模式（提交后立即返回）</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
public final class CdcFrameworkMain {

    private static final Logger log = LoggerFactory.getLogger(CdcFrameworkMain.class);

    private CdcFrameworkMain() {
    }

    /**
     * 主入口。
     *
     * @param args 命令行参数
     * @throws Exception 作业执行异常
     */
    public static void main(String[] args) throws Exception {
        String configPath = null;
        boolean async = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--config 需要指定文件路径");
                    }
                    configPath = args[++i];
                }
                case "--async" -> async = true;
                default -> log.warn("忽略未知参数: {}", args[i]);
            }
        }

        if (configPath == null) {
            throw new IllegalArgumentException("必须通过 --config 指定 YAML 配置文件路径");
        }

        log.info("加载 CDC 配置: {}", configPath);
        CdcYamlConfig yamlConfig = CdcYamlConfig.load(Path.of(configPath));

        CdcFramework framework = CdcFramework.builder()
                .jobName(yamlConfig.getJobName())
                .parallelism(yamlConfig.getParallelism())
                .blocking(!async)
                .build();

        for (SourceConfig source : yamlConfig.getSources()) {
            framework.addSource(source);
        }
        for (SinkConfig sink : yamlConfig.getSinks()) {
            framework.addSink(sink);
        }

        log.info("启动 CDC 作业 '{}'，{} 源 → {} 目标",
                yamlConfig.getJobName(),
                yamlConfig.getSources().size(),
                yamlConfig.getSinks().size());

        framework.execute();
    }
}