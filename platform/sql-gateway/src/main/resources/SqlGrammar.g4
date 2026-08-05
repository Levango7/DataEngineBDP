/*
 * SqlGrammar.g4 — 数擎大数据平台 SQL 网关 ANTLR4 语法文件（简化版）
 *
 * <p>本文件作为 SQL 语法的形式化定义文档，描述 ANSI SQL + Hive + Doris + Trino 方言
 * 的核心子集。实际解析由 {@code SqlParserService} 中的手写递归下降解析器完成，
 * 不依赖 ANTLR4 生成代码，以避免构建复杂性与额外依赖。</p>
 *
 * <p>如需启用 ANTLR4 生成，可在 pom.xml 中添加 antlr4-maven-plugin 并将本文件
 * 置于 {@code src/main/antlr4/com/shuqing/bigdata/sqlgateway/parser/} 目录。</p>
 *
 * @author shuqing-bigdata
 */
grammar SqlGrammar;

// ====== Parser Rules ======

/** 顶层语句 */
statement
    : selectStatement
    | insertStatement
    | createTableStatement
    | dropStatement
    | alterStatement
    ;

/** SELECT 查询 */
selectStatement
    : SELECT selectItem (',' selectItem)*
      fromClause?
      whereClause?
      groupByClause?
      havingClause?
      orderByClause?
      limitClause?
    ;

selectItem
    : '*' | expression (AS? alias)?
    ;

fromClause
    : FROM tableReference (joinClause)*
    ;

tableReference
    : tableName (AS? alias)?
    | '(' selectStatement ')' (AS? alias)?
    ;

joinClause
    : (INNER | LEFT | RIGHT | FULL | CROSS)? JOIN tableReference ON expression
    ;

whereClause
    : WHERE expression
    ;

groupByClause
    : GROUP BY expression (',' expression)*
    ;

havingClause
    : HAVING expression
    ;

orderByClause
    : ORDER BY orderItem (',' orderItem)*
    ;

orderItem
    : expression (ASC | DESC)?
    ;

limitClause
    : LIMIT INTEGER
    ;

/** INSERT 语句 */
insertStatement
    : INSERT INTO tableName ('(' columnName (',' columnName)* ')')?
      (VALUES '(' value (',' value)* ')' | selectStatement)
    ;

/** CREATE TABLE 语句 */
createTableStatement
    : CREATE (EXTERNAL)? TABLE (IF NOT EXISTS)? tableName
      '(' columnDef (',' columnDef)* ')'
      (PARTITIONED BY '(' columnDef (',' columnDef)* ')')?   // Hive
      (STORED AS identifier)?                                  // Hive
      (DISTRIBUTED BY HASH '(' columnName (',' columnName)* ')')?  // Doris
      (PROPERTIES '(' property (',' property)* ')')?          // Doris
    ;

columnDef
    : columnName dataType
    ;

property
    : STRING '=' STRING
    ;

/** DROP 语句 */
dropStatement
    : DROP TABLE (IF EXISTS)? tableName
    ;

/** ALTER 语句 */
alterStatement
    : ALTER TABLE tableName alterAction
    ;

alterAction
    : ADD COLUMN columnDef
    | DROP COLUMN columnName
    | RENAME TO tableName
    ;

/** 表名（可含数据库前缀） */
tableName
    : identifier ('.' identifier)?
    ;

columnName
    : identifier ('.' identifier)?
    ;

/** 表达式（简化） */
expression
    : literal
    | columnName
    | functionCall
    | '(' expression ')'
    | expression op=('*'|'/'|'+'|'-') expression
    | expression op=(AND | OR) expression
    | expression op=('=' | '!=' | '<' | '>' | '<=' | '>=') expression
    | NOT expression
    | expression IN '(' (selectStatement | expression (',' expression)*) ')'
    | expression BETWEEN expression AND expression
    | expression LIKE STRING
    ;

functionCall
    : identifier '(' (expression (',' expression)*)? ')'
    ;

literal
    : INTEGER | DECIMAL | STRING | NULL | TRUE | FALSE
    ;

alias
    : identifier
    ;

identifier
    : IDENTIFIER | quotedIdentifier
    ;

quotedIdentifier
    : '`' IDENTIFIER '`'
    ;

// ====== Lexer Rules ======

SELECT: 'SELECT';
FROM: 'FROM';
WHERE: 'WHERE';
JOIN: 'JOIN';
INNER: 'INNER';
LEFT: 'LEFT';
RIGHT: 'RIGHT';
FULL: 'FULL';
OUTER: 'OUTER';
CROSS: 'CROSS';
ON: 'ON';
GROUP: 'GROUP';
BY: 'BY';
HAVING: 'HAVING';
ORDER: 'ORDER';
ASC: 'ASC';
DESC: 'DESC';
LIMIT: 'LIMIT';
UNION: 'UNION';
ALL: 'ALL';
INSERT: 'INSERT';
INTO: 'INTO';
VALUES: 'VALUES';
CREATE: 'CREATE';
EXTERNAL: 'EXTERNAL';
TABLE: 'TABLE';
IF: 'IF';
NOT: 'NOT';
EXISTS: 'EXISTS';
DROP: 'DROP';
ALTER: 'ALTER';
ADD: 'ADD';
COLUMN: 'COLUMN';
RENAME: 'RENAME';
TO: 'TO';
AS: 'AS';
AND: 'AND';
OR: 'OR';
IN: 'IN';
BETWEEN: 'BETWEEN';
LIKE: 'LIKE';
NULL: 'NULL';
TRUE: 'TRUE';
FALSE: 'FALSE';
PARTITIONED: 'PARTITIONED';
STORED: 'STORED';
DISTRIBUTED: 'DISTRIBUTED';
HASH: 'HASH';
PROPERTIES: 'PROPERTIES';

IDENTIFIER: [a-zA-Z_][a-zA-Z0-9_]*;
INTEGER: [0-9]+;
DECIMAL: [0-9]+'.'[0-9]+;
STRING: '\'' (~'\'' | '\'\'' )* '\'';

WS: [ \t\r\n]+ -> skip;