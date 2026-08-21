package com.shuqing.bigdata.governance.lineage.service;

import com.shuqing.bigdata.governance.lineage.model.LineageEdge;
import com.shuqing.bigdata.governance.lineage.model.LineageNode;
import com.vesoft.nebula.client.graph.NebulaPoolConfig;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.net.NebulaPool;
import com.vesoft.nebula.client.graph.net.Session;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * NebulaGraph 图存储客户端封装。
 *
 * <p>基于 nebula-java 3.x 的 {@link NebulaPool}（连接池）+ {@link Session} 实现，
 * 仅在 {@code nebula.enabled=true} 时由 Spring 容器实例化（见
 * {@link ConditionalOnProperty}）。连接/初始化失败时降级为不可用（
 * {@link #isAvailable()} 返回 {@code false}），不影响 H2/JPA 主流程。</p>
 *
 * <p>初始化流程：
 * <ol>
 *   <li>初始化连接池 {@link NebulaPool#init}</li>
 *   <li>认证并切换图空间（{@code USE `<space>`}），若空间不存在则自动创建
 *       （{@code CREATE SPACE IF NOT EXISTS}，VID 类型 FIXED_STRING(512)）</li>
 *   <li>幂等创建 Tag {@code lineage_node} 与 Edge {@code lineage_edge}（IF NOT EXISTS）</li>
 * </ol>
 *
 * <p>nGQL 写入语义：
 * <ul>
 *   <li>顶点：{@code INSERT VERTEX lineage_node(...) VALUES "fullName":(...)}</li>
 *   <li>边：{@code INSERT EDGE lineage_edge(...) VALUES "src"->"dst":(...)}</li>
 * </ul>
 * INSERT VERTEX/EDGE 默认覆盖同 VID/同 src→dst 的已有数据，天然幂等。</p>
 *
 * @author shuqing-bigdata
 */
@Component
@ConditionalOnProperty(prefix = "nebula", name = "enabled", havingValue = "true")
public class NebulaGraphClient {

    private static final Logger log = LoggerFactory.getLogger(NebulaGraphClient.class);

    /** 顶点 Tag 名 */
    private static final String TAG_NODE = "lineage_node";
    /** 边 Edge 名 */
    private static final String EDGE_LINEAGE = "lineage_edge";

    private final NebulaPool pool;
    private final String username;
    private final String password;
    private final String space;
    private volatile boolean available;

    /**
     * 构造 NebulaGraph 客户端，完成连接池初始化与 Schema 就绪检查。
     *
     * <p>任何步骤失败均只记录警告日志并将 {@link #available} 置为 {@code false}，
     * 不抛出异常，以保证应用启动不被可选后端阻断。</p>
     *
     * @param host     NebulaGraph graphd 主机
     * @param port     NebulaGraph graphd 端口
     * @param username 用户名
     * @param password 密码
     * @param space    图空间名
     */
    public NebulaGraphClient(
            @Value("${nebula.host:127.0.0.1}") String host,
            @Value("${nebula.port:9669}") int port,
            @Value("${nebula.username:root}") String username,
            @Value("${nebula.password:nebula}") String password,
            @Value("${nebula.space:lineage}") String space) {

        this.username = username;
        this.password = password;
        this.space = space;
        this.pool = new NebulaPool();
        this.available = false;

        try {
            NebulaPoolConfig config = new NebulaPoolConfig()
                    .setMinConnSize(1)
                    .setMaxConnSize(10)
                    .setTimeout(3000)
                    .setIdleTime(0)
                    .setIntervalIdle(-1)
                    .setWaitTime(0);
            List<HostAddress> addresses = Collections.singletonList(new HostAddress(host, port));
            if (!pool.init(addresses, config)) {
                log.warn("NebulaGraph 连接池初始化失败: host={}:{}", host, port);
                return;
            }

            Session session = null;
            try {
                session = pool.getSession(username, password, true);
                if (!ensureSpace(session)) {
                    return;
                }
                ensureSchema(session);
            } finally {
                releaseQuietly(session);
            }

            this.available = true;
            log.info("NebulaGraph 客户端就绪: host={}:{}, space={}", host, port, space);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("NebulaGraph 初始化被中断，降级为 H2-only: host={}:{}, space={}", host, port, space);
        } catch (Exception e) {
            log.warn("NebulaGraph 初始化失败，降级为 H2-only: host={}:{}, error={}", host, port, e.getMessage());
        }
    }

    /**
     * 切换图空间；若空间不存在则创建后等待生效再切换。
     *
     * @param session 已认证会话
     * @return 切换成功返回 true
     * @throws InterruptedException 等待空间生效时被中断
     * @throws com.vesoft.nebula.client.graph.exception.IOErrorException nGQL 执行 IO 异常
     */
    private boolean ensureSpace(Session session) throws InterruptedException,
            com.vesoft.nebula.client.graph.exception.IOErrorException {
        ResultSet rs = session.execute("USE `" + space + "`;");
        if (rs.isSucceeded()) {
            return true;
        }
        log.info("图空间 [{}] 不存在或切换失败，尝试自动创建: {}", space, rs.getErrorMessage());
        session.execute("CREATE SPACE IF NOT EXISTS `" + space + "` "
                + "(vid_type=FIXED_STRING(512), partition_num=10, replica_factor=1);");
        // NebulaGraph 创建 space 后需等待 2 个心跳周期（默认 1s × 2）生效
        Thread.sleep(2000);
        rs = session.execute("USE `" + space + "`;");
        if (!rs.isSucceeded()) {
            log.warn("NebulaGraph 切换图空间失败: space={}, error={}", space, rs.getErrorMessage());
            return false;
        }
        return true;
    }

    /**
     * 幂等创建 Tag 与 Edge；创建后等待 schema 生效。
     *
     * @param session 已切换至目标空间的会话
     * @throws InterruptedException 等待 schema 生效时被中断
     * @throws com.vesoft.nebula.client.graph.exception.IOErrorException nGQL 执行 IO 异常
     */
    private void ensureSchema(Session session) throws InterruptedException,
            com.vesoft.nebula.client.graph.exception.IOErrorException {
        session.execute("CREATE TAG IF NOT EXISTS `" + TAG_NODE + "` ("
                + "full_name string(512), node_type string(16), "
                + "schema_name string(128), table_name string(128), "
                + "column_name string(128), display_name string(256));");
        session.execute("CREATE EDGE IF NOT EXISTS `" + EDGE_LINEAGE + "` ("
                + "relation_type string(32), source_sql string(4096), "
                + "dialect string(16), expression string(1024));");
        // 等待 Tag/Edge schema 在集群内生效
        Thread.sleep(2000);
    }

    /**
     * 客户端是否可用（连接池已初始化且 Schema 就绪）。
     *
     * @return 可用返回 true；初始化失败或已关闭返回 false
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * 执行单条 nGQL 语句（自动先 {@code USE `<space>`} 切换图空间）。
     *
     * @param ngql nGQL 语句（不含 USE）
     * @return 执行成功返回 true；失败或客户端不可用返回 false
     */
    public boolean execute(String ngql) {
        if (!available) {
            return false;
        }
        Session session = null;
        try {
            session = pool.getSession(username, password, true);
            session.execute("USE `" + space + "`;");
            ResultSet rs = session.execute(ngql);
            if (!rs.isSucceeded()) {
                log.warn("nGQL 执行失败: [{}], error={}", ngql, rs.getErrorMessage());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("nGQL 执行异常: [{}], error={}", ngql, e.getMessage());
            return false;
        } finally {
            releaseQuietly(session);
        }
    }

    /**
     * 写入一个血缘节点顶点到 NebulaGraph。
     *
     * <p>nGQL：{@code INSERT VERTEX lineage_node(...) VALUES "fullName":(...)}</p>
     *
     * @param node 血缘节点
     * @return 写入成功返回 true
     */
    public boolean writeNode(LineageNode node) {
        if (!available || node == null || node.getFullName() == null) {
            return false;
        }
        String vid = escape(node.getFullName());
        StringBuilder ngql = new StringBuilder(256);
        ngql.append("INSERT VERTEX `").append(TAG_NODE).append("`")
                .append("(full_name, node_type, schema_name, table_name, column_name, display_name) ")
                .append("VALUES \"").append(vid).append("\":(\"")
                .append(escape(node.getFullName())).append("\", \"")
                .append(escape(node.getNodeType() != null ? node.getNodeType().name() : "")).append("\", \"")
                .append(escape(node.getSchemaName())).append("\", \"")
                .append(escape(node.getTableName())).append("\", \"")
                .append(escape(node.getColumnName())).append("\", \"")
                .append(escape(node.getDisplayName())).append("\");");
        return execute(ngql.toString());
    }

    /**
     * 写入一条血缘边到 NebulaGraph。
     *
     * <p>nGQL：{@code INSERT EDGE lineage_edge(...) VALUES "src"->"dst":(...)}</p>
     *
     * @param edge 血缘边
     * @return 写入成功返回 true
     */
    public boolean writeEdge(LineageEdge edge) {
        if (!available || edge == null
                || edge.getSourceFullName() == null || edge.getTargetFullName() == null) {
            return false;
        }
        StringBuilder ngql = new StringBuilder(256);
        ngql.append("INSERT EDGE `").append(EDGE_LINEAGE).append("`")
                .append("(relation_type, source_sql, dialect, expression) ")
                .append("VALUES \"").append(escape(edge.getSourceFullName())).append("\"->\"")
                .append(escape(edge.getTargetFullName())).append("\":(\"")
                .append(escape(edge.getRelationType() != null ? edge.getRelationType().name() : "")).append("\", \"")
                .append(escape(edge.getSourceSql())).append("\", \"")
                .append(escape(edge.getDialect())).append("\", \"")
                .append(escape(edge.getExpression())).append("\");");
        return execute(ngql.toString());
    }

    /**
     * 转义 nGQL 字符串字面量中的特殊字符（反斜杠与双引号）。
     *
     * @param s 原始字符串，null 视为空串
     * @return 转义后字符串
     */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 安静地释放会话回连接池，吞掉所有异常。
     *
     * @param session 待释放会话，null 时直接返回
     */
    private static void releaseQuietly(Session session) {
        if (session == null) {
            return;
        }
        try {
            session.release();
        } catch (Exception ignored) {
            // 释放异常不影响主流程
        }
    }

    /**
     * 销毁时关闭连接池。
     */
    @PreDestroy
    public void close() {
        try {
            pool.close();
        } catch (Exception ignored) {
            // 关闭异常忽略
        }
        log.info("NebulaGraph 客户端已关闭");
    }
}