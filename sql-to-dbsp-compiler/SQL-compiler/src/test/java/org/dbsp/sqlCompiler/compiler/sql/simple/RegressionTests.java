package org.dbsp.sqlCompiler.compiler.sql.simple;

import org.dbsp.sqlCompiler.circuit.DBSPCircuit;
import org.dbsp.sqlCompiler.circuit.operator.DBSPJoinFilterMapOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPMapIndexOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPMapOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPPartitionedRollingAggregateWithWaterlineOperator;
import org.dbsp.sqlCompiler.compiler.DBSPCompiler;
import org.dbsp.sqlCompiler.compiler.TestUtil;
import org.dbsp.sqlCompiler.compiler.backend.rust.ToRustVisitor;
import org.dbsp.sqlCompiler.compiler.sql.tools.CompilerCircuitStream;
import org.dbsp.sqlCompiler.compiler.sql.tools.SqlIoTest;
import org.dbsp.sqlCompiler.compiler.visitors.inner.InnerVisitor;
import org.dbsp.sqlCompiler.compiler.visitors.outer.CircuitVisitor;
import org.dbsp.sqlCompiler.ir.expression.DBSPApplyExpression;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class RegressionTests extends SqlIoTest {
    @Test
    public void issue3114() {
        this.compileRustTestCase("""
                CREATE TABLE T(x integer);
                LATENESS V.x 1;
                CREATE VIEW V
                    AS SELECT
                        x,
                        MAP['x', x] AS map,
                        ARRAY[1, x] as a,
                        ROW(1, x) as r
                    FROM T;""");
    }

    @Test
    public void latenessType() {
        this.statementsFailingInCompilation("""
                CREATE TABLE T(x integer);
                LATENESS V.x INTERVAL '10' SECONDS;
                CREATE VIEW V AS SELECT
                   x,
                   MAP['x', x] AS map
                FROM T;""", "Cannot apply '-' to arguments of type '<INTEGER> - <INTERVAL SECOND>'");
    }

    @Test
    public void issue3109() {
        var ccs = this.getCCS("""
                CREATE TYPE other_type AS (s string);
                
                CREATE TYPE my_type AS (
                    other other_type ARRAY,
                    s string
                );
                
                CREATE TABLE t (content my_type ARRAY);
                
                create view v as (
                    select content_flat.*
                    from t, unnest(t.content) as content_flat
                );""");
        CircuitVisitor visitor = new CircuitVisitor(ccs.compiler) {
            @Override
            public void postorder(DBSPMapOperator operator) {
                throw new RuntimeException("Map operator should have been eliminated");
            }
        };
        visitor.apply(ccs.circuit);
    }

    @Test
    public void issue3086() {
        this.getCCS("""
                CREATE TABLE map_tbl(c1 MAP<VARCHAR, INT>, c2 MAP<VARCHAR, INT>);
                CREATE MATERIALIZED VIEW v AS SELECT
                ARG_MAX(c1, c2) AS arg_max,
                ARG_MIN(c1, c2) AS arg_min
                FROM map_tbl;""");
    }

    @Test
    public void issue3083() {
        var ccs = this.getCCS("""
                CREATE TABLE timestamp_tbl(c1 TIMESTAMP, c2 TIMESTAMP);
                CREATE LOCAL VIEW atbl_interval_months AS SELECT
                (c1 - c2)MONTH AS c1_minus_c2,
                (c2 - c1)MONTH AS c2_minus_c1
                FROM timestamp_tbl;
                
                CREATE VIEW atbl_interval_months_res AS SELECT
                (c1_minus_c2) = -(c2_minus_c1) AS eq
                FROM atbl_interval_months;""");
        ccs.step("""
                INSERT INTO timestamp_tbl VALUES('2019-12-05 08:27:00', '2014-11-05 12:45:00');
                INSERT INTO timestamp_tbl VALUES('2020-06-21 14:00:00', '2023-02-26 18:00:00');""",
                """ 
                 eq   | weight
                ------------------
                 true | 1
                 true | 1""");
        this.addRustTestCase(ccs);
    }

    @Test
    public void issue3095() {
        var ccs = this.getCCS("""
                CREATE FUNCTION udf(input INT) RETURNS INT;
                CREATE TABLE T (p int);
                
                CREATE LOCAL VIEW V0 AS
                SELECT udf(p) as f FROM T;
                
                CREATE VIEW V1 AS
                SELECT f+1, f+2 FROM V0;""");
        int[] functionCalls = new int[] { 0 };
        InnerVisitor visitor = new InnerVisitor(ccs.compiler) {
            @Override
            public void postorder(DBSPApplyExpression node) {
                ++functionCalls[0];
            }
        };
        visitor.getCircuitVisitor(false).apply(ccs.circuit);
        assert functionCalls[0] == 1;
    }

    @Test
    public void issue3070() {
        this.compileRustTestCase("""
                CREATE TABLE t0(c0 INT);
                CREATE MATERIALIZED VIEW v20_optimized AS (SELECT ASCII(CHR(t0.c0)) FROM t0);""");
    }

    @Test
    public void issue3071() {
        this.statementsFailingInCompilation("""
                CREATE TABLE t0(c0 char) with ('materialized' = 'true');
                CREATE MATERIALIZED VIEW v100_optimized AS (SELECT * FROM t0 WHERE (t0.c0) NOT BETWEEN ('Y') AND ('䤈'));""",
                "Failed to encode");
        this.statementsFailingInCompilation("""
                CREATE MATERIALIZED VIEW v73_optimized AS SELECT 'a'>'헊';""",
                "Failed to encode");
    }

    @Test
    public void issue3072() {
        this.compileRustTestCase("""
                CREATE TABLE t0(c0 VARCHAR);
                CREATE VIEW v271_optimized AS
                SELECT COUNT(RLIKE(t0.c0, '0.5123590946084831')) FROM t0;""");
        this.compileRustTestCase("""
                CREATE TABLE t0(c0 DOUBLE, c1 INT, c2 BIGINT);
                CREATE MATERIALIZED VIEW v293_optimized AS SELECT COUNT(RLIKE(SUBSTRING('', t0.c1), '')) FROM t0;""");
    }

    @Test
    public void issue3073() {
        this.compileRustTestCase("""
                CREATE MATERIALIZED VIEW v10_optimized AS
                SELECT SOME(('2059-10-5 5:3:37') NOT BETWEEN ('2171-1-14 22:23:8') AND ((-7::TINYINT)::TIMESTAMP));""");
    }

    @Test
    public void issue3076() {
        this.statementsFailingInCompilation("CREATE VIEW v AS SELECT SUM(NULL);",
                "Argument of aggregate has NULL type");
        this.compileRustTestCase("CREATE VIEW V AS SELECT SUM(CAST(NULL AS INTEGER));");
    }

    @Test
    public void issue3063() {
        this.getCCS("""
                CREATE TABLE array_tbl(c1 INT ARRAY, c2 INT ARRAY);
                CREATE MATERIALIZED VIEW v AS SELECT
                ARG_MIN(c1, c2) AS arg_min,
                ARG_MAX(c1, c2) AS arg_max
                FROM array_tbl;""");
    }

    @Test
    public void issue3035() {
        this.compileRustTestCase("""
                CREATE TABLE t0(c0 INT) with ('materialized' = 'true');
                CREATE TABLE t4(c0 DOUBLE) with ('materialized' = 'true');
                CREATE VIEW v11_optimized AS (SELECT COUNT(*) FROM t0, t4 WHERE IS_INF(ROUND(t4.c0, t0.c0)));""");
    }

    @Test
    public void issue3038() {
        this.compileRustTestCase("""
                CREATE TABLE t0(c0 VARCHAR) with ('materialized' = 'true');
                CREATE VIEW v3_optimized AS (SELECT COUNT(t0.c0) FROM t0 WHERE ((RLIKE(t0.c0, t0.c0))<(LOWER(t0.c0))));""");
    }

    @Test
    public void issue3039() {
        this.compileRustTestCase(
                "CREATE VIEW v1_optimized AS SELECT MIN(MOD(POWER(-1553189232, 1981020635), 1981020635))");
    }

    @Test
    public void issue3040() {
        this.compileRustTestCase("""
                CREATE TABLE t5(c0 BOOLEAN, c1 INT, c2 INT) with ('materialized' = 'true');
                CREATE MATERIALIZED VIEW v40_optimized AS (SELECT SUM(t5.c2) FROM t5 WHERE (('X')<(CHR(t5.c2))));""");
    }

    @Test
    public void issue3041() {
        this.statementsFailingInCompilation("CREATE MATERIALIZED VIEW v199 AS SELECT TRIM();",
                "Error parsing SQL: Encountered \")\" at line 1");
    }

    @Test
    public void issue3042() {
        this.compileRustTestCase("""
                CREATE TABLE t3(c0 DOUBLE) with ('materialized' = 'true');
                CREATE TABLE t4(c0 VARCHAR, c1 INT, c2 VARCHAR, c3 BOOLEAN, c4 BOOLEAN) with ('materialized' = 'true');
                CREATE VIEW v29_optimized AS (SELECT AVG(TRUNCATE((t3.c0::DOUBLE), t4.c1)) FROM t4, t3);""");
    }

    @Test
    public void issue3043() {
        this.compileRustTestCase("""
                CREATE TABLE t1(c0 VARCHAR, c1 DOUBLE, c2 DOUBLE) with ('materialized' = 'true');
                CREATE TABLE t4(c0 BOOLEAN) with ('materialized' = 'true');
                CREATE VIEW v22_optimized AS (SELECT * FROM t4, t1 WHERE ((t4.c0)IS NOT DISTINCT FROM(IS_INF(t1.c1))));""");
    }

    @Test
    public void issue3030() {
        this.compileRustTestCase("""
                CREATE TABLE timestamp_tbl(c1 TIMESTAMP, c2 TIMESTAMP);
                
                CREATE LOCAL VIEW atbl_interval AS SELECT
                (c1 - c2) YEAR AS c1_minus_c2
                FROM timestamp_tbl;
                
                CREATE LOCAL VIEW interval_minus_interval AS SELECT
                ((c1_minus_c2) - INTERVAL '2' YEAR) AS c1
                FROM atbl_interval;
                
                CREATE MATERIALIZED VIEW interval_minus_interval_seconds AS SELECT
                CAST((c1) AS BIGINT) AS f_c1
                FROM interval_minus_interval;""");
    }

    @Test
    public void issue457() {
        String sql = """
                create table t (id int, x int);
                create view v as
                select max(t.x) OVER (PARTITION BY t.id)
                from t;""";
        this.compileRustTestCase(sql);
    }

    @Test
    public void issue3031() {
        this.compileRustTestCase("""
                CREATE TABLE time_tbl(c1 TIME, c2 TIME);
                
                CREATE LOCAL VIEW atbl_interval AS SELECT
                (c1 - c2)MINUTE AS c1_minus_c2
                FROM time_tbl;
                
                CREATE MATERIALIZED VIEW interval_negation AS SELECT
                '18:30:00':: TIME + (-c1_minus_c2) AS c1
                FROM atbl_interval;""");
    }

    @Test
    public void issue2943() {
        this.compileRustTestCase("""
                CREATE TABLE x(c1 TIMESTAMP, c2 TIMESTAMP);
                CREATE VIEW v AS SELECT (c1 - c2) SECONDS FROM x;""");
    }

    @Test
    public void recursionCrash() {
        // View defined but not used was producing a compiler crash
        String sql = "DECLARE RECURSIVE VIEW ba (id BIGINT);";
        this.getCCS(sql);
    }

    @Test
    public void issue2651() {
        this.compileRustTestCase("""
                CREATE TYPE INT32 AS INTEGER;
                CREATE TYPE AI AS INT ARRAY;
                CREATE TABLE T(x INT32, y AI);""");
    }

    @Test @Ignore("https://issues.apache.org/jira/browse/CALCITE-6681")
    public void issueLateral() {
        // This triggers https://issues.apache.org/jira/browse/CALCITE-6681
        // If we disable PROJECT_CORRELATE_TRANSPOSE, it fails due to the decorrelator
        this.compileRustTestCase("""
                CREATE TABLE t2 (
                  a VARCHAR,
                  ts INT,
                  x BIGINT
                );
                
                CREATE VIEW v AS
                WITH t1(a, ts) AS (VALUES('a', 1))
                SELECT * FROM t1
                LEFT JOIN LATERAL (
                    SELECT x
                    FROM t2
                    WHERE t2.a = t1.a
                      AND t2.ts <= t1.ts
                    LIMIT 1
                ) ON true
                LEFT JOIN LATERAL (
                    SELECT x
                    FROM t2
                    WHERE t2.a = t1.a
                ) ON true;""");
    }

    @Test
    public void issue2639() {
        String sql = """
                CREATE TABLE t (
                    bin BINARY
                ) with ('materialized' = 'true');
                
                CREATE FUNCTION nbin2nbin(i BINARY NOT NULL) RETURNS BINARY NOT NULL AS i;
                
                CREATE MATERIALIZED VIEW v AS
                SELECT
                    nbin2nbin(bin)
                FROM t;""";
        this.compileRustTestCase(sql);
    }

    @Test
    public void issue2942() {
        this.statementsFailingInCompilation(
                "CREATE VIEW v1(c0) AS (SELECT '9,\uE8C7voz[*');",
                "Failed to encode");
    }

    @Test
    public void issue2642() {
        String sql = """
                create table t (
                    v VARIANT
                );
                create view v as select COALESCE(v, VARIANTNULL()) from t;
                """;
        this.compileRustTestCase(sql);
    }

    @Test
    public void issue2640() {
        String sql = """
                CREATE TABLE t (
                    bin VARBINARY
                ) with ('materialized' = 'true');
                CREATE FUNCTION nbin2nbin(i VARBINARY NOT NULL) RETURNS VARBINARY NOT NULL AS CAST(x'ABCD' as VARBINARY);
                CREATE MATERIALIZED VIEW v AS
                SELECT
                    nbin2nbin(bin)
                FROM t;""";
        this.compileRustTestCase(sql);
    }

    @Test
    public void issue2641() {
        String sql = """
                create table t (
                    d DATE
                );
                create view v as select COALESCE(d, '2023-01-01') from t;
                """;
        this.compileRustTestCase(sql);
    }

    @Test
    public void existingFunction() {
        String sql = """
                CREATE FUNCTION regexp_replace(s VARCHAR, p VARCHAR, pos INTEGER)
                RETURNS VARCHAR NOT NULL AS CAST('foo' as VARCHAR);""";
        this.statementsFailingInCompilation(sql,
                "A function named 'regexp_replace' is already predefined");
    }

    @Test
    public void testUnnestWithOrdinality() {
        String sql = """
                CREATE TABLE data
                (CITIES VARCHAR ARRAY, COUNTRY VARCHAR);
                CREATE VIEW v as
                SELECT city, country
                FROM data, UNNEST(cities) WITH ORDINALITY AS city;""";
        var ccs = this.getCCS(sql);
        this.addRustTestCase(ccs);
    }

    @Test
    public void testUnnest2() {
        String sql = """
                CREATE TYPE ResourceSpans AS (
                    r int,
                    scopeSpans int ARRAY
                );
                CREATE TABLE t (resourceSpans ResourceSpans ARRAY);
                
                CREATE MATERIALIZED VIEW resource_spans AS
                SELECT resourceSpans.scopeSpans
                FROM t, UNNEST(resourceSpans) WITH ORDINALITY as resourceSpans;""";
        this.compileRustTestCase(sql);
    }

    @Test
    public void testUnnest() {
        String sql = """
                CREATE TABLE tx (outputs VARIANT ARRAY);
                
                CREATE MATERIALIZED VIEW outs0 AS (
                    select output['output_type'] as json_output from tx, UNNEST(outputs) AS t (output)
                );
                
                CREATE MATERIALIZED VIEW outs1 AS (
                    select PARSE_JSON(CAST(output AS STRING)) from tx, UNNEST(outputs) AS t (output)
                );
                
                CREATE MATERIALIZED VIEW outs2 AS (
                    select (CAST(output AS VARIANT))['output_type'] from tx, UNNEST(outputs) AS t (output)
                );
                """;
        var ccs = this.getCCS(sql);
        this.addRustTestCase(ccs);
    }

    @Test
    public void issue3006() {
        this.statementsFailingInCompilation("""
                CREATE TABLE user(id BIGINT);
                CREATE TABLE t(user_id BIGINT);
                
                CREATE MATERIALIZED VIEW v AS
                SELECT t.user_id
                FROM user, t
                WHERE user.id = t.user_id;""",
                "Table 'user' has the same name as a predefined function");
    }

    @Test
    public void issue2638() {
        String sql = """
                CREATE TABLE t (
                    m MAP<VARCHAR, VARCHAR>
                ) with ('materialized' = 'true');
                
                CREATE FUNCTION map2map(i MAP<VARCHAR, VARCHAR>) RETURNS MAP<VARCHAR, VARCHAR>;
                CREATE FUNCTION nmap2nmap(i MAP<VARCHAR, VARCHAR> NOT NULL) RETURNS MAP<VARCHAR, VARCHAR> NOT NULL;
                
                CREATE MATERIALIZED VIEW v AS
                SELECT
                    map2map(m),
                    nmap2nmap(m)
                FROM t;""";
        // This is not executed, since the udfs have no definitions
        var ccs = this.getCCS(sql);
        // Test that code generation does not crash
        ToRustVisitor.toRustString(ccs.compiler, ccs.circuit, ccs.compiler.options);
    }

    @Test
    public void tableAfterView() {
        // Test that tables created after views
        // are inserted in Rust before views
        String sql = """
               CREATE TABLE t(id int);
               CREATE MATERIALIZED VIEW test AS
               SELECT * FROM t;
               CREATE TABLE s (id int);
               """;
        this.compileRustTestCase(sql);
    }

    @Test
    public void vecStringCast() {
        String sql = "CREATE VIEW V AS SELECT CAST(ARRAY [1,2,3] AS VARCHAR)";
        this.statementsFailingInCompilation(sql,
                "Cast function cannot convert value of type INTEGER ARRAY to type VARCHAR");
    }

    @Test
    public void issue2539() {
        String sql = """
                CREATE TABLE t(c1 INT, c2 INT);
                CREATE VIEW v AS SELECT
                ARRAY_AGG(c1) FILTER(WHERE (c1+c2)>3)
                FROM t;""";
        CompilerCircuitStream ccs = this.getCCS(sql);
        ccs.step("INSERT INTO t VALUES (2, 3), (5, 6), (2, 1);",
                """
                         result | weight
                        ------------------
                         {2,5} | 1""");
        this.addRustTestCase(ccs);
    }

    @Test
    public void issue2649() {
        String sql = """
                CREATE TABLE T(id INT, c1 INT, c2 INT);
                CREATE VIEW V AS SELECT
                ARG_MIN(c1, c1) FILTER(WHERE c2 > 2) AS f_c1,
                ARG_MAX(c2, c2) FILTER(WHERE id = 1) AS f_c2
                FROM T;""";
        var ccs = this.getCCS(sql);
        ccs.step("INSERT INTO T VALUES (0, 5, 8), (1, 4, 2), (0, NULL, 3), (1, NULL, 5);",
                """
                 f_c1 | f_c2 | weight
                ----------------------
                    5 |    5 |     1""");
        this.addRustTestCase(ccs);
    }

    @Test
    public void issue2316() {
        String sql = """
                CREATE TABLE sum(c1 TINYINT);
                CREATE VIEW sum_view AS SELECT SUM(c1) AS c1 FROM sum;
                """;
        CompilerCircuitStream ccs = this.getCCS(sql);
        ccs.step("INSERT INTO sum VALUES (127), (1);",
                " result" +
                        "---------");
        this.addFailingRustTestCase("issue2316", "overflow", ccs);
    }

    @Test
    public void testVariantCast() {
        String sql = """
                CREATE TABLE variant_table(val VARIANT);
                CREATE VIEW typed_view AS SELECT
                    CAST(val['scores'] AS DECIMAL ARRAY) as scores
                FROM variant_table;""";
        this.compileRustTestCase(sql);
    }

    @Test
    public void timestamp() {
        String sql = """
                CREATE FUNCTION MAKE_TIMESTAMP(SECONDS BIGINT) RETURNS TIMESTAMP AS
                TIMESTAMPADD(SECOND, SECONDS, DATE '1970-01-01');

                CREATE TABLE T(c1 BIGINT);
                CREATE VIEW sum_view AS SELECT MAKE_TIMESTAMP(c1) FROM T;
                """;
        CompilerCircuitStream ccs = this.getCCS(sql);
        ccs.step("INSERT INTO T VALUES (10000000);",
                """
                         result | weight
                        -------------------
                         1970-04-26 17:46:40 | 1""");
        this.addRustTestCase(ccs);
    }

    @Test
    public void testFpCast() {
        String sql = """
                CREATE TABLE TAB2 (COL0 INTEGER, COL1 INTEGER, COL2 INTEGER);
                CREATE VIEW V100 AS
                SELECT 99 * - COR0.COL0
                FROM TAB2 AS COR0
                WHERE NOT - COR0.COL2 * + CAST(+ COR0.COL0 AS REAL) >= + (- COR0.COL2)
                """;
        this.compileRustTestCase(sql);
    }

    @Test
    public void testTPCHQ5Simple() {
        String sql = """
                CREATE TABLE LINEITEM ( L_ORDERKEY    INTEGER NOT NULL,
                                        L_PARTKEY     INTEGER NOT NULL,
                                        L_SUPPKEY     INTEGER NOT NULL,
                                        L_LINENUMBER  INTEGER NOT NULL,
                                        L_QUANTITY    DECIMAL(15,2) NOT NULL,
                                        L_EXTENDEDPRICE  DECIMAL(15,2) NOT NULL,
                                        L_DISCOUNT    DECIMAL(15,2) NOT NULL,
                                        L_TAX         DECIMAL(15,2) NOT NULL,
                                        L_RETURNFLAG  CHAR(1) NOT NULL,
                                        L_LINESTATUS  CHAR(1) NOT NULL,
                                        L_SHIPDATE    DATE NOT NULL,
                                        L_COMMITDATE  DATE NOT NULL,
                                        L_RECEIPTDATE DATE NOT NULL,
                                        L_SHIPINSTRUCT CHAR(25) NOT NULL,
                                        L_SHIPMODE     CHAR(10) NOT NULL,
                                        L_COMMENT      VARCHAR(44) NOT NULL);

                create view q5 as
                select
                    sum(l_extendedprice * (1 - l_discount)) as revenue
                from
                    lineitem
                """;
        this.compileRustTestCase(sql);
    }

    @Test
    public void decimalMult() {
        String sql = """
                CREATE TABLE T(C DECIMAL(16, 2));
                CREATE VIEW V AS SELECT 100.20 * T.C FROM T;""";
        CompilerCircuitStream ccs = this.getCCS(sql);
        ccs.step("INSERT INTO T VALUES (100.0)",
                """
                         value  | weight
                        ----------------
                          10020 | 1""");
        this.addRustTestCase(ccs);
    }

    @Test
    public void issue2438() {
        String sql = """
                CREATE TABLE t(
                   id INT, c1 REAL,
                   c2 REAL NOT NULL);
                CREATE VIEW v AS SELECT
                   AVG(c1) FILTER (WHERE c2 > -3802.271) AS f_c1,
                   AVG(c2) FILTER (WHERE c2 > -3802.271) AS f_c2\s
                FROM t;""";
        CompilerCircuitStream ccs = this.getCCS(sql);
        ccs.step("""
                INSERT INTO t VALUES
                (0, -1111.5672,  2231.790),
                (0, NULL, -3802.271),
                (1, 57681.08, 71689.8057),
                (1, 57681.08, 87335.89658)""",
                """
                 c1       | c2      | weight
                -----------------------------
                 38083.53 | 53752.5 | 1""");
        this.addRustTestCase(ccs);
    }

    @Test
    public void issue2350() {
        String sql = """
                CREATE TABLE arg_min(c1 TINYINT NOT NULL);
                CREATE VIEW arg_min_view AS SELECT
                ARG_MIN(c1, c1) AS c1
                FROM arg_min""";
        CompilerCircuitStream ccs = this.getCCS(sql);
        ccs.step("""
                INSERT INTO arg_min VALUES (2), (3), (5)""",
                """
                 value | weight
                ----------------
                  2    | 1""");
        this.addRustTestCase(ccs);
    }

    @Test
    public void issue2261() {
        String sql = """
                CREATE TABLE stddev_groupby(id INT, c2 TINYINT NOT NULL);
                CREATE VIEW stddev_view AS SELECT
                STDDEV_SAMP(c2) AS c2
                FROM stddev_groupby
                GROUP BY id;""";
        this.compileRustTestCase(sql);
    }

    @Test
    public void testLag() {
        String sql = """
                CREATE TABLE foo (
                    id INT NOT NULL,
                    card_id INT,
                    ttime INT
                );

                CREATE VIEW bar AS
                select
                    id,
                    ttime,
                    lag(ttime, 1) OVER (PARTITION BY card_id ORDER BY ttime) as lag1,
                    lag(ttime, 2) OVER (PARTITION BY card_id ORDER BY ttime) as lag2
                from foo;""";

        // Validated on postgres
        CompilerCircuitStream ccs = this.getCCS(sql);
        ccs.step("""
                INSERT INTO foo VALUES(2, 2, 10);
                INSERT INTO foo VALUES(2, 2, 10);
                INSERT INTO foo VALUES(30, 2, 12);
                INSERT INTO foo VALUES(50, 2, 13);""",
                """
                 id | ttime | lag1 | lag2 | weight
                ------------------------------------
                 2  | 10   |      |        | 1
                 2  | 10   | 10   |        | 1
                 30 | 12   | 10   | 10     | 1
                 50 | 13   | 12   | 10     | 1""");
        this.addRustTestCase(ccs);
    }

    @Test
    public void issue2333() {
        this.q("""
                SELECT TIMESTAMP '1970-01-01 00:00:00' - INTERVAL 1 HOURS;
                 result
                --------
                 1969-12-31 23:00:00""");
    }

    @Test
    public void issue2315() {
        String sql = """
                CREATE TABLE T(id INT, c6 INT NOT NULL);

                CREATE VIEW stddev_view AS
                SELECT id, STDDEV_SAMP(c6) AS c6
                FROM T
                GROUP BY id;""";
        CompilerCircuitStream ccs = this.getCCS(sql);
        ccs.step("""
                       INSERT INTO T VALUES(0, 6);
                       INSERT INTO T VALUES(1, 3);""",
                """
                        id | c6 | weight
                       -----------------
                         0 |    | 1
                         1 |    | 1""");
        this.addRustTestCase(ccs);
    }

    @Test
    public void issue2090() {
        String sql = """
                CREATE TABLE example (
                    id INT
                ) WITH (
                    'connector' = 'value1'
                                  'value2'
                );""";
        this.statementsFailingInCompilation(sql, "Expected a simple string");
    }

    @Test
    public void issue2201() {
        String sql = """
                create table customer_address(
                    ca_zip char(10)
                );

                CREATE VIEW V AS SELECT substr(ca_zip,1,5)
                FROM customer_address;""";
        this.compileRustTestCase(sql);
    }

    @Test
    public void issue1189() {
        String sql = """
                create table EVENT_DURATION_V(duration bigint, event_type_id bigint);
                create table EVENTTYPE_T(id bigint, name string);

                CREATE VIEW SHORTEST_ALARMS_TYPE_V AS
                SELECT duration
                ,      event_type_id
                ,      ett.name
                FROM   (SELECT duration
                        ,      event_type_id
                        ,      ROW_NUMBER() OVER (PARTITION BY event_type_id
                                                  ORDER BY duration ASC) AS rnum
                        FROM   EVENT_DURATION_V) a
                        JOIN EVENTTYPE_T ett ON a.event_type_id = ett.id
                WHERE   rnum <= 1
                ;""";
        this.compileRustTestCase(sql);
    }

    @Test
    public void issue2017() {
        String sql = """
                CREATE TABLE customer (
                    c_id INT NOT NULL,
                    c_d_id INT NOT NULL,
                    c_w_id INT NOT NULL,
                    c_first VARCHAR(16),
                    c_middle CHAR(2),
                    c_last VARCHAR(16),
                    c_street_1 VARCHAR(20),
                    c_street_2 VARCHAR(20),
                    c_city VARCHAR(20),
                    c_state CHAR(2),
                    c_zip CHAR(9),
                    c_phone CHAR(16),
                    c_since TIMESTAMP,
                    c_credit CHAR(2),
                    c_credit_lim DECIMAL(12,2),
                    c_discount DECIMAL(4,4),
                    c_balance DECIMAL(12,2),
                    c_ytd_payment DECIMAL(12,2),
                    c_payment_cnt INT,
                    c_delivery_cnt INT,
                    c_data VARCHAR(500),
                    PRIMARY KEY (c_w_id, c_d_id, c_id),
                    FOREIGN KEY (c_w_id, c_d_id) REFERENCES district(d_w_id, d_id)
                );

                CREATE TABLE transaction_parameters (
                    txn_id INT NOT NULL PRIMARY KEY,
                    w_id INT,
                    d_id INT,
                    c_id INT,
                    c_w_id INT,
                    c_d_id INT,
                    c_last VARCHAR(20), -- TODO check
                    h_amount DECIMAL(5,2),
                    h_date TIMESTAMP,
                    datetime_ TIMESTAMP
                );

                CREATE VIEW cust_max AS
                SELECT c.c_first, c.c_middle, c.c_id,
                    c.c_street_1, c.c_street_2, c.c_city, c.c_state, c.c_zip,
                    c.c_phone, c.c_credit, c.c_credit_lim,
                    c.c_discount, c.c_balance, c.c_since
                FROM customer AS c,
                     transaction_parameters AS t
                WHERE c.c_last = t.c_last
                  AND c.c_d_id = t.c_d_id
                  AND c.c_w_id = t.c_w_id
                  AND c_first = (select max(c_first) from customer LIMIT 1)
                LIMIT 1;""";
        this.compileRustTestCase(sql);
    }

    @Test
    public void issue2094() {
        String sql = "CREATE FUNCTION F() RETURNS INT NOT NULL AS 0;";
        this.compileRustTestCase(sql);
    }

    @Test
    public void issue2095() {
        String sql = """
        CREATE FUNCTION F() RETURNS INT NOT NULL AS 0;
        CREATE FUNCTION G() RETURNS INT NOT NULL AS F();
        """;
        this.compileRustTestCase(sql);
    }

    @Test
    public void testErrorPosition() {
        // Test that errors for functions report correct source position
        String sql = """
                CREATE FUNCTION ANCHOR_TIMESTAMP() RETURNS TIMESTAMP NOT NULL
                  AS TIMESTAMP '2024-01-01 00:00:00';

                CREATE FUNCTION ROUND_TIMESTAMP(ts TIMESTAMP, billing_interval_days INT) RETURNS TIMESTAMP
                  AS TRUNC(DATEDIFF(DAYS, ts, ANCHOR_TIMESTAMP())) + ANCHOR_TIMESTAMP();
                """;
        this.statementsFailingInCompilation(sql, """
                    5|  AS TRUNC(DATEDIFF(DAYS, ts, ANCHOR_TIMESTAMP())) + ANCHOR_TIMESTAMP();
                           ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                """);
    }

    @Test
    public void issue1868() {
        String sql = """
                CREATE TABLE example_a (
                    id INT NOT NULL
                );

                CREATE TABLE example_b (
                    id INT NOT NULL
                );

                CREATE VIEW example_c AS (
                    SELECT COALESCE(example_a.id, 0) - COALESCE(example_b.id, 0)
                    FROM example_a
                         FULL JOIN example_b
                         ON example_a.id = example_b.id
                );""";
        this.compileRustTestCase(sql);
    }

    // Test for https://github.com/feldera/feldera/issues/1151
    @Test
    public void issue1151() {
        String sql = "CREATE TABLE event_t ( id BIGINT NOT NULL PRIMARY KEY, local_event_dt DATE )";
        this.compileRustTestCase(sql);
    }

    @Test
    public void testFilterPull() {
        // Example used in a blog post
        String sql = """
                CREATE TABLE transaction (
                   cc_num int
                );

                CREATE TABLE users (
                   cc_num int,
                   id bigint,
                   age int
                );

                CREATE VIEW transaction_with_user AS
                SELECT
                    transaction.*,
                    users.id as user_id,
                    users.age
                FROM
                    transaction JOIN users
                ON users.cc_num = transaction.cc_num
                WHERE
                    users.age >= 21;""";
        DBSPCompiler compiler = this.testCompiler();
        compiler.compileStatements(sql);
        DBSPCircuit circuit = getCircuit(compiler);
        CircuitVisitor visitor = new CircuitVisitor(compiler) {
            int filterJoin = 0;

            @Override
            public void postorder(DBSPJoinFilterMapOperator operator) {
                this.filterJoin++;
            }

            @Override
            public void endVisit() {
                // If the filter for age is not pulled above the join, it
                // will produce a JoinFilterMap operator.
                Assert.assertEquals(0, this.filterJoin);
            }
        };
        visitor.apply(circuit);
    }

    @Test
    public void issue1898() {
        String sql = """
                create table t(
                    id bigint,
                    part bigint
                );

                create view v as
                SELECT
                    id,
                    COUNT(DISTINCT id) FILTER (WHERE id > 100) OVER window_100 AS agg
                FROM
                    t
                WINDOW
                    window_100 AS (PARTITION BY part ORDER BY id RANGE BETWEEN 100 PRECEDING AND CURRENT ROW);""";
        DBSPCompiler compiler = this.testCompiler();
        compiler.options.languageOptions.throwOnError = false;
        compiler.compileStatements(sql);
        TestUtil.assertMessagesContain(compiler, "OVER must be applied to aggregate function");
    }

    @Test
    public void issue2027() {
        // validated with Postgres 15
        String sql = """
                CREATE TABLE T (
                   id INT,
                   amt INT,
                   ts TIMESTAMP
                );

                CREATE VIEW V AS SELECT
                    id,
                    amt,
                    SUM(amt) OVER window1 AS s1,
                    SUM(amt) OVER window2 AS s2,
                    SUM(amt) OVER window3 AS s3,
                    SUM(amt) OVER window4 AS s4
                FROM T WINDOW
                window1 AS (PARTITION BY id ORDER BY EXTRACT(HOUR FROM ts) RANGE BETWEEN 2 PRECEDING AND 2 FOLLOWING),
                window2 AS (PARTITION BY id ORDER BY ts RANGE BETWEEN INTERVAL 1 HOUR PRECEDING AND INTERVAL 1 MINUTE FOLLOWING),
                window3 AS (PARTITION BY id ORDER BY CAST(ts AS DATE) RANGE BETWEEN INTERVAL 1 HOUR PRECEDING AND INTERVAL 1 MINUTE FOLLOWING),
                window4 AS (PARTITION BY id ORDER BY CAST(ts AS TIME) RANGE BETWEEN INTERVAL 1 HOUR PRECEDING AND INTERVAL 1 MINUTE FOLLOWING);""";
           CompilerCircuitStream ccs = this.getCCS(sql);
        ccs.step("""
                        INSERT INTO T VALUES(0, 1, '2024-01-01 00:00:00');
                        INSERT INTO T VALUES(1, 2, '2024-01-01 00:00:00');
                        INSERT INTO T VALUES(0, 3, '2024-01-01 00:00:01');
                        INSERT INTO T VALUES(0, 4, '2024-01-01 00:00:01');
                        INSERT INTO T VALUES(0, 5, '2024-01-01 00:10:00');
                        INSERT INTO T VALUES(0, 6, '2024-01-01 00:11:00');
                        INSERT INTO T VALUES(0, 7, '2024-01-01 00:13:00');""",
                """
                        id | amt | s1 | s2 | s3 | s4 | weight
                       ---------------------------------------
                        0  | 1   | 26 | 8  | 26 | 8  | 1
                        0  | 3   | 26 | 8  | 26 | 8  | 1
                        0  | 4   | 26 | 8  | 26 | 8  | 1
                        0  | 5   | 26 | 19 | 26 | 19 | 1
                        0  | 6   | 26 | 19 | 26 | 19 | 1
                        0  | 7   | 26 | 26 | 26 | 26 | 1
                        1  | 2   | 2  | 2  | 2  | 2  | 1"""
        );
        this.addRustTestCase(ccs);
    }

    @Test
    public void issue2027negative() {
        this.statementsFailingInCompilation("""
                CREATE TABLE t (
                   id INT,
                   amt INT,
                   ts TIMESTAMP
                );

                CREATE VIEW V AS SELECT
                    SUM(amt) OVER window1 AS s1
                FROM t WINDOW
                window1 AS (PARTITION BY id ORDER BY ts RANGE BETWEEN INTERVAL 1 MONTH PRECEDING AND INTERVAL 1 YEAR FOLLOWING);""",
                "Can you rephrase the query using an interval");
    }

    @Test
    public void issue2027negative1() {
        this.statementsFailingInCompilation("""
                CREATE TABLE t (
                   id INT,
                   amt INT,
                   ts TIMESTAMP
                );

                CREATE VIEW V AS SELECT
                    SUM(amt) OVER window1 AS s1
                FROM t WINDOW
                window1 AS (PARTITION BY id ORDER BY ts RANGE BETWEEN INTERVAL -1 HOUR PRECEDING AND CURRENT ROW);""",
                "Window bounds must be positive");
    }

    @Test
    public void issue1768() {
        String sql = """
                CREATE TABLE transaction (
                    trans_date_time TIMESTAMP NOT NULL LATENESS INTERVAL 1 DAY,
                    cc_num BIGINT NOT NULL,
                    merchant STRING,
                    category STRING,
                    amt FLOAT64,
                    trans_num STRING,
                    unix_time INTEGER NOT NULL,
                    merch_lat FLOAT64 NOT NULL,
                    merch_long FLOAT64 NOT NULL,
                    is_fraud INTEGER
                );

                CREATE TABLE demographics (
                    cc_num BIGINT NOT NULL,
                    first STRING,
                    gender STRING,
                    street STRING,
                    city STRING,
                    state STRING,
                    zip INTEGER,
                    lat FLOAT64,
                    long FLOAT64,
                    city_pop INTEGER,
                    job STRING,
                    dob STRING
                );

                CREATE VIEW V AS SELECT
                    transaction.cc_num,
                    CASE
                      WHEN dayofweek(trans_date_time) IN(6, 7) THEN true
                      ELSE false
                    END AS is_weekend,
                    CASE
                      WHEN hour(trans_date_time) <= 6 THEN true
                      ELSE false
                    END AS is_night,
                    category,
                    AVG(amt) OVER window_1_day AS avg_spend_pd,
                    AVG(amt) OVER window_7_day AS avg_spend_pw,
                    AVG(amt) OVER window_30_day AS avg_spend_pm,
                    COUNT(*) OVER window_1_day AS trans_freq_24,
                      amt, state, job, unix_time, city_pop, is_fraud
                  FROM transaction
                  JOIN demographics
                  ON transaction.cc_num = demographics.cc_num
                  WINDOW
                    window_1_day AS (PARTITION BY transaction.cc_num ORDER BY unix_time RANGE BETWEEN 86400 PRECEDING AND CURRENT ROW),
                    window_7_day AS (PARTITION BY transaction.cc_num ORDER BY unix_time RANGE BETWEEN 604800 PRECEDING AND CURRENT ROW),
                    window_30_day AS (PARTITION BY transaction.cc_num ORDER BY unix_time RANGE BETWEEN 2592000 PRECEDING AND CURRENT ROW);""";

        DBSPCompiler compiler = this.testCompiler();
        compiler.options.languageOptions.incrementalize = true;
        compiler.compileStatements(sql);
        DBSPCircuit circuit = getCircuit(compiler);
        CircuitVisitor visitor = new CircuitVisitor(compiler) {
            int mapIndex = 0;

            @Override
            public void postorder(DBSPMapIndexOperator operator) {
                this.mapIndex++;
            }

            @Override
            public void endVisit() {
                // We expect 7 MapIndex operators instead of 11 if CSE works
                Assert.assertEquals(7, this.mapIndex);
            }
        };
        visitor.apply(circuit);
    }

    @Test
    public void issue3128() {
        this.compileRustTestCase("""
                CREATE TABLE t2(c2 VARCHAR) with ('materialized' = 'true');
                CREATE MATERIALIZED VIEW v106_optimized AS (SELECT SUBSTRING(t2.c2, 1.36683) FROM t2);""");
    }

    @Test
    public void missingCast() {
        String sql = """
                create table TRANSACTION (unix_time BIGINT LATENESS 0);
                """;
        this.compileRustTestCase(sql);
    }

    @Test
    public void testDiv() {
        this.qs("""
            SELEct 95.0/100;
             r
            ----
             0.95
            (1 row)

            SELEct 95/100.0;
             r
            ----
             0.95
            (1 row)""");
    }

    @Test @Ignore("Calcite decorrelator fails")
    public void issue1956() {
        String sql = """
                CREATE TABLE auctions (
                  id INT NOT NULL PRIMARY KEY,
                  seller INT,
                  item TEXT
                );

                CREATE TABLE bids (
                  id INT NOT NULL PRIMARY KEY,
                  buyer INT,
                  auction_id INT,
                  amount INT
                );

                CREATE VIEW V AS SELECT id, (SELECT array_agg(buyer) FROM (
                  SELECT buyer FROM bids WHERE auction_id = auctions.id
                  ORDER BY buyer LIMIT 10
                )) FROM auctions;""";
        this.compileRustTestCase(sql);
    }

    @Test
    public void issue1957() {
        String sql = """
                CREATE TABLE warehouse (
                   id INT NOT NULL PRIMARY KEY,
                   parentId INT
                );

                CREATE VIEW V AS SELECT\s
                  id,
                  (SELECT ARRAY_AGG(id) FROM (
                    SELECT id FROM warehouse WHERE parentId = warehouse.id
                    ORDER BY id LIMIT 10
                  )) AS first_children
                FROM warehouse;""";
        this.compileRustTestCase(sql);
    }

    @Test
    public void issue1793() {
        String sql = """
                CREATE TABLE transaction_demographics (
                    trans_date_time TIMESTAMP,
                    cc_num BIGINT NOT NULL,
                    category STRING,
                    amt FLOAT64,
                    unix_time INTEGER NOT NULL LATENESS 86400,
                    first STRING,
                    state STRING,
                    job STRING,
                    city_pop INTEGER,
                    is_fraud BOOLEAN
                );

                CREATE VIEW V AS SELECT
                    cc_num,
                    CASE
                      WHEN dayofweek(trans_date_time) IN(6, 7) THEN true
                      ELSE false
                    END AS is_weekend,
                    CASE
                      WHEN hour(trans_date_time) <= 6 THEN true
                      ELSE false
                    END AS is_night,
                    category,
                    AVG(amt) OVER window_1_day AS avg_spend_pd,
                    AVG(amt) OVER window_7_day AS avg_spend_pw,
                    AVG(amt) OVER window_30_day AS avg_spend_pm,
                    COUNT(*) OVER window_1_day AS trans_freq_24,
                      amt, state, job, unix_time, city_pop, is_fraud
                  FROM transaction_demographics
                  WINDOW
                    window_1_day AS (PARTITION BY cc_num ORDER BY unix_time RANGE BETWEEN 86400 PRECEDING AND CURRENT ROW),
                    window_7_day AS (PARTITION BY cc_num ORDER BY unix_time RANGE BETWEEN 604800 PRECEDING AND CURRENT ROW),
                    window_30_day AS (PARTITION BY cc_num ORDER BY unix_time RANGE BETWEEN 2592000 PRECEDING AND CURRENT ROW);""";
        DBSPCompiler compiler = this.testCompiler();
        compiler.options.languageOptions.incrementalize = true;
        compiler.compileStatements(sql);
        DBSPCircuit circuit = getCircuit(compiler);
        CircuitVisitor visitor = new CircuitVisitor(compiler) {
            int count = 0;

            @Override
            public void postorder(DBSPPartitionedRollingAggregateWithWaterlineOperator operator) {
                this.count++;
            }

            @Override
            public void endVisit() {
                Assert.assertEquals(3, this.count);
            }
        };
        visitor.apply(circuit);
        this.compileRustTestCase(sql);
    }

    @Test
    public void issue3093() {
        this.compileRustTestCase("""
                CREATE TABLE t1(c0 REAL);
                CREATE VIEW v0 AS
                SELECT t1.c0 + 0.42331, t1.c0 * 0.42331, .42331 * t1.c0 FROM t1;""");
    }

    @Test
    public void issue3094() {
        this.compileRustTestCase("""
                CREATE TABLE t1(c0 REAL, c1 VARCHAR, c2 CHAR);
                CREATE VIEW v2_optimized AS SELECT MIN(t1.c1) FROM t1 WHERE (1 IS NOT DISTINCT FROM 2);""");
    }
}
