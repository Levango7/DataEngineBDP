package com.levango7.dataenginebdp.governance.lineage.service;

import com.levango7.dataenginebdp.governance.lineage.analyzer.ColumnLineageExtractor;
import com.levango7.dataenginebdp.governance.lineage.analyzer.LineageRelation;
import com.levango7.dataenginebdp.governance.lineage.analyzer.TableLineageExtractor;
import com.levango7.dataenginebdp.governance.lineage.model.LineageEdge;
import com.levango7.dataenginebdp.governance.lineage.model.LineageGraph;
import com.levango7.dataenginebdp.governance.lineage.model.LineageNode;
import com.levango7.dataenginebdp.sqlgateway.parser.ASTNode;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlParserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 血缘分析核心服务。
 *
 * <p>编排 SQL 解析、表级/字段级血缘提取、图谱构建与持久化：
 * <ol>
 *   <li>调用 {@link SqlParserService#parse} 得到 AST</li>
 *   <li>{@link TableLineageExtractor} 提取表级血缘</li>
 *   <li>{@link ColumnLineageExtractor} 提取字段级血缘</li>
 *   <li>构建 {@link LineageGraph}（含节点与边）</li>
 *   <li>{@link LineageGraphWriter} 持久化到 H2 / NebulaGraph</li>
 * </ol>
 *
 * @author shuqing-bigdata
 */
@Service
public class LineageAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(LineageAnalyzerService.class);

    private final SqlParserService parser;
    private final TableLineageExtractor tableExtractor;
    private final ColumnLineageExtractor columnExtractor;
    private final LineageGraphWriter graphWriter;

    /**
     * 构造服务。
     *
     * @param parser         SQL 解析器
     * @param graphWriter    图谱写入器
     */
    @Autowired
    public LineageAnalyzerService(SqlParserService parser, LineageGraphWriter graphWriter) {
        this.parser = parser;
        this.tableExtractor = new TableLineageExtractor(parser);
        this.columnExtractor = new ColumnLineageExtractor(parser);
        this.graphWriter = graphWriter;
    }

    /**
     * 分析 SQL 血缘（自动检测方言）。
     *
     * @param sql SQL 文本
     * @return 血缘图谱
     */
    public LineageGraph analyze(String sql) {
        return analyze(sql, null);
    }

    /**
     * 分析 SQL 血缘。
     *
     * @param sql     SQL 文本
     * @param dialect 方言；{@code null} 时自动检测
     * @return 血缘图谱
     */
    public LineageGraph analyze(String sql, SqlDialect dialect) {
        long start = System.currentTimeMillis();
        if (sql == null || sql.isBlank()) {
            return new LineageGraph(sql, dialect == null ? "AUTO" : dialect.name(), 0);
        }

        // 1. 解析 AST
        SqlDialect effectiveDialect = dialect;
        ASTNode ast;
        if (dialect == null) {
            // 自动检测方言并记录
            effectiveDialect = parser.detectDialect(sql);
            ast = parser.parse(sql, effectiveDialect);
        } else {
            ast = parser.parse(sql, dialect);
        }
        String dialectName = effectiveDialect.name();

        // 2. 提取表级 + 字段级血缘
        List<LineageRelation> tableRelations = tableExtractor.extractFromAst(ast, sql, dialect);
        List<LineageRelation> columnRelations = columnExtractor.extractFromAst(ast, sql, dialect);

        // 3. 构建图谱
        long elapsed = System.currentTimeMillis() - start;
        LineageGraph graph = new LineageGraph(sql, dialectName, elapsed);
        buildGraph(graph, tableRelations, columnRelations);

        // 4. 持久化
        try {
            graphWriter.write(graph);
        } catch (Exception e) {
            log.warn("图谱持久化失败，仅返回内存图: {}", e.getMessage());
        }

        log.info("血缘分析完成: {} 个节点, {} 条边, 耗时 {}ms",
                graph.getNodes().size(), graph.getEdges().size(),
                graph.getAnalyzeTimeMs());
        return graph;
    }

    /**
     * 构建内存图谱（节点 + 边）。
     */
    private void buildGraph(LineageGraph graph,
                            List<LineageRelation> tableRelations,
                            List<LineageRelation> columnRelations) {
        // 表级节点与边
        for (LineageRelation rel : tableRelations) {
            graph.addNode(buildTableNode(rel.getSource()));
            graph.addNode(buildTableNode(rel.getTarget()));
            graph.addEdge(rel.toEdge());
        }
        // 字段级节点与边
        for (LineageRelation rel : columnRelations) {
            graph.addNode(buildColumnNode(rel.getSource()));
            graph.addNode(buildColumnNode(rel.getTarget()));
            graph.addEdge(rel.toEdge());
        }
    }

    /**
     * 构造表节点：解析 db.table 形式。
     */
    private LineageNode buildTableNode(String fullName) {
        LineageNode node = new LineageNode(fullName, LineageNode.NodeType.TABLE);
        String[] parts = fullName.split("\\.", 2);
        if (parts.length == 2) {
            node.setSchemaName(parts[0]);
            node.setTableName(parts[1]);
            node.setDisplayName(parts[1]);
        } else {
            node.setTableName(fullName);
            node.setDisplayName(fullName);
        }
        return node;
    }

    /**
     * 构造字段节点：解析 db.table.column 形式。
     */
    private LineageNode buildColumnNode(String fullName) {
        LineageNode node = new LineageNode(fullName, LineageNode.NodeType.COLUMN);
        String[] parts = fullName.split("\\.");
        if (parts.length == 3) {
            node.setSchemaName(parts[0]);
            node.setTableName(parts[1]);
            node.setColumnName(parts[2]);
            node.setDisplayName(parts[1] + "." + parts[2]);
        } else if (parts.length == 2) {
            node.setTableName(parts[0]);
            node.setColumnName(parts[1]);
            node.setDisplayName(fullName);
        } else {
            node.setColumnName(fullName);
            node.setDisplayName(fullName);
        }
        return node;
    }
}