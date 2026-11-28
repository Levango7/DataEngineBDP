package com.levango7.dataenginebdp.sqlgateway.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlStatementGuardTest {

    @Test
    @DisplayName("单条只读语句放行")
    void readOnly_shouldPass() {
        assertThat(SqlStatementGuard.isSingleReadOnlyStatement("SELECT 1")).isTrue();
        assertThat(SqlStatementGuard.isSingleReadOnlyStatement("WITH t AS (SELECT 1) SELECT * FROM t")).isTrue();
        assertThat(SqlStatementGuard.isSingleReadOnlyStatement("SHOW TABLES")).isTrue();
        assertThat(SqlStatementGuard.isSingleReadOnlyStatement("DESC t")).isTrue();
        assertThat(SqlStatementGuard.isSingleReadOnlyStatement("EXPLAIN SELECT 1")).isTrue();
        assertThat(SqlStatementGuard.isSingleReadOnlyStatement("SELECT 1;")).isTrue();
    }

    @Test
    @DisplayName("写入语句拒绝")
    void write_shouldFail() {
        assertThat(SqlStatementGuard.isSingleReadOnlyStatement("INSERT INTO t VALUES (1)")).isFalse();
        assertThat(SqlStatementGuard.isSingleReadOnlyStatement("UPDATE t SET a=1")).isFalse();
        assertThat(SqlStatementGuard.isSingleReadOnlyStatement("DELETE FROM t")).isFalse();
        assertThat(SqlStatementGuard.isSingleReadOnlyStatement("DROP TABLE t")).isFalse();
    }

    @Test
    @DisplayName("多语句拒绝")
    void multiStatement_shouldFail() {
        assertThat(SqlStatementGuard.isSingleReadOnlyStatement("SELECT 1; SELECT 2")).isFalse();
        assertThat(SqlStatementGuard.isSingleReadOnlyStatement("SELECT 1; DROP TABLE t")).isFalse();
    }

    @Test
    @DisplayName("注释与字符串中的分号/关键字不造成误判")
    void commentsAndStrings_shouldNotConfuseGuard() {
        assertThat(SqlStatementGuard.isSingleReadOnlyStatement(
                "SELECT ';' FROM t -- ; DROP TABLE t")).isTrue();
        assertThat(SqlStatementGuard.isSingleReadOnlyStatement(
                "SELECT /* ; DROP TABLE t */ 1")).isTrue();
        assertThat(SqlStatementGuard.isSingleReadOnlyStatement(
                "SELECT 'x; DROP TABLE t' FROM t")).isTrue();
    }

    @Test
    @DisplayName("EXPLAIN 包裹写入语句拒绝")
    void explainWrite_shouldFail() {
        assertThat(SqlStatementGuard.isSingleReadOnlyStatement("EXPLAIN INSERT INTO t VALUES (1)")).isFalse();
    }
}
