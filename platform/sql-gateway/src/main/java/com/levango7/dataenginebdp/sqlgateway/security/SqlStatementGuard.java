package com.levango7.dataenginebdp.sqlgateway.security;

import java.util.Locale;
import java.util.Set;

/**
 * SQL 语句级只读门禁。
 *
 * <p>前缀白名单只能挡住开头，无法识别多语句、注释包裹或 EXPLAIN 包裹的写入语句；
 * 本类在网关层做语句级判定：仅允许单条 SELECT/WITH/SHOW/DESC/DESCRIBE，
 * EXPLAIN 递归校验其内部语句，并拒绝多语句。</p>
 */
public final class SqlStatementGuard {

    private static final Set<String> READ_ONLY_HEAD = Set.of(
            "SELECT", "WITH", "SHOW", "DESC", "DESCRIBE"
    );

    private SqlStatementGuard() {
    }

    /**
     * 判断 SQL 是否为单条只读语句。
     *
     * @param sql 原始 SQL 文本
     * @return true 表示可下发后端
     */
    public static boolean isSingleReadOnlyStatement(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        String stripped = stripComments(sql);
        String withoutTrailingSemicolons = stripTrailingSemicolons(stripped);
        if (withoutTrailingSemicolons.isBlank()
                || countTopLevelSemicolons(withoutTrailingSemicolons) > 0) {
            return false;
        }
        return isReadOnlyStatement(withoutTrailingSemicolons.trim());
    }

    private static boolean isReadOnlyStatement(String sql) {
        String first = firstKeyword(sql);
        if (READ_ONLY_HEAD.contains(first)) {
            return true;
        }
        if ("EXPLAIN".equals(first)) {
            String rest = sql.substring("EXPLAIN".length()).trim();
            return !rest.isBlank() && isSingleReadOnlyStatement(rest);
        }
        return false;
    }

    private static String firstKeyword(String sql) {
        int i = 0;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < sql.length()
                && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
            i++;
        }
        return sql.substring(start, i).toUpperCase(Locale.ROOT);
    }

    private static String stripComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean single = false;
        boolean dquote = false;
        boolean backtick = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    out.append(c);
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (single) {
                out.append(c);
                if (c == '\'' && next == '\'') {
                    out.append(next);
                    i++;
                } else if (c == '\'') {
                    single = false;
                }
                continue;
            }
            if (dquote) {
                out.append(c);
                if (c == '"' && next == '"') {
                    out.append(next);
                    i++;
                } else if (c == '"') {
                    dquote = false;
                }
                continue;
            }
            if (backtick) {
                out.append(c);
                if (c == '`' && next == '`') {
                    out.append(next);
                    i++;
                } else if (c == '`') {
                    backtick = false;
                }
                continue;
            }
            if (c == '\'') {
                single = true;
                out.append(c);
            } else if (c == '"') {
                dquote = true;
                out.append(c);
            } else if (c == '`') {
                backtick = true;
                out.append(c);
            } else if (c == '-' && next == '-') {
                lineComment = true;
            } else if (c == '/' && next == '*') {
                blockComment = true;
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String stripTrailingSemicolons(String sql) {
        int end = sql.length();
        while (end > 0 && Character.isWhitespace(sql.charAt(end - 1))) {
            end--;
        }
        while (end > 0 && sql.charAt(end - 1) == ';') {
            end--;
            while (end > 0 && Character.isWhitespace(sql.charAt(end - 1))) {
                end--;
            }
        }
        return sql.substring(0, end);
    }

    private static int countTopLevelSemicolons(String sql) {
        int count = 0;
        boolean single = false;
        boolean dquote = false;
        boolean backtick = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (single) {
                if (c == '\'' && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i++;
                } else if (c == '\'') {
                    single = false;
                }
            } else if (dquote) {
                if (c == '"' && i + 1 < sql.length() && sql.charAt(i + 1) == '"') {
                    i++;
                } else if (c == '"') {
                    dquote = false;
                }
            } else if (backtick) {
                if (c == '`' && i + 1 < sql.length() && sql.charAt(i + 1) == '`') {
                    i++;
                } else if (c == '`') {
                    backtick = false;
                }
            } else if (c == '\'') {
                single = true;
            } else if (c == '"') {
                dquote = true;
            } else if (c == '`') {
                backtick = true;
            } else if (c == ';') {
                count++;
            }
        }
        return count;
    }
}
