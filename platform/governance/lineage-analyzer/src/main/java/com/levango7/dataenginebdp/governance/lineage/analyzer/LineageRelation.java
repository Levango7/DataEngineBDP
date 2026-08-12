package com.levango7.dataenginebdp.governance.lineage.analyzer;

import com.levango7.dataenginebdp.governance.lineage.model.LineageEdge;

import java.util.Objects;

/**
 * 血缘关系（提取器产出，写入图谱前中间表示）。
 *
 * <p>描述一条 {@code source → target} 的血缘，附带关系类型与可选元信息。</p>
 *
 * @author shuqing-bigdata
 */
public class LineageRelation {

    /** 关系类型 */
    public enum RelationType {
        /** 表级血缘 */
        TABLE_LINEAGE,
        /** 字段级血缘 */
        COLUMN_LINEAGE
    }

    private final String source;
    private final String target;
    private final RelationType relationType;
    private final String expression;
    private final String sourceSql;
    private final String dialect;

    /**
     * 构造表级血缘关系。
     *
     * @param source 源表全名
     * @param target 目标表全名
     */
    public LineageRelation(String source, String target) {
        this(source, target, RelationType.TABLE_LINEAGE, null, null, null);
    }

    /**
     * 构造血缘关系。
     *
     * @param source       源全名
     * @param target       目标全名
     * @param relationType 关系类型
     * @param expression   转换表达式（字段级）
     * @param sourceSql    源 SQL
     * @param dialect      方言
     */
    public LineageRelation(String source, String target, RelationType relationType,
                           String expression, String sourceSql, String dialect) {
        this.source = source;
        this.target = target;
        this.relationType = relationType;
        this.expression = expression;
        this.sourceSql = sourceSql;
        this.dialect = dialect;
    }

    public String getSource() {
        return source;
    }

    public String getTarget() {
        return target;
    }

    public RelationType getRelationType() {
        return relationType;
    }

    public String getExpression() {
        return expression;
    }

    public String getSourceSql() {
        return sourceSql;
    }

    public String getDialect() {
        return dialect;
    }

    /**
     * 转换为持久化边对象。
     *
     * @return {@link LineageEdge}
     */
    public LineageEdge toEdge() {
        LineageEdge.RelationType type = relationType == RelationType.TABLE_LINEAGE
                ? LineageEdge.RelationType.TABLE_LINEAGE
                : LineageEdge.RelationType.COLUMN_LINEAGE;
        LineageEdge edge = new LineageEdge(source, target, type);
        edge.setSourceSql(sourceSql);
        edge.setDialect(dialect);
        edge.setExpression(expression);
        return edge;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LineageRelation other)) {
            return false;
        }
        return Objects.equals(source, other.source)
                && Objects.equals(target, other.target)
                && relationType == other.relationType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, target, relationType);
    }

    @Override
    public String toString() {
        return source + " -> " + target + " (" + relationType + ')';
    }
}