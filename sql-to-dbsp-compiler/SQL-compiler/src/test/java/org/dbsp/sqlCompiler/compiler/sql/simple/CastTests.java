/*
 * Copyright 2022 VMware, Inc.
 * SPDX-License-Identifier: MIT
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.dbsp.sqlCompiler.compiler.sql.simple;

import org.dbsp.sqlCompiler.compiler.DBSPCompiler;
import org.dbsp.sqlCompiler.compiler.frontend.calciteObject.CalciteObject;
import org.dbsp.sqlCompiler.compiler.sql.tools.Change;
import org.dbsp.sqlCompiler.compiler.sql.tools.CompilerCircuitStream;
import org.dbsp.sqlCompiler.compiler.sql.tools.InputOutputChange;
import org.dbsp.sqlCompiler.compiler.sql.tools.SqlIoTest;
import org.dbsp.sqlCompiler.ir.expression.DBSPTupleExpression;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPDecimalLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPDoubleLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPI32Literal;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPI64Literal;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPStringLiteral;
import org.dbsp.sqlCompiler.ir.expression.DBSPZSetExpression;
import org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeDecimal;
import org.dbsp.util.Linq;
import org.junit.Test;

import java.math.BigDecimal;

public class CastTests extends SqlIoTest {
    final DBSPTypeDecimal tenTwo = new DBSPTypeDecimal(CalciteObject.EMPTY, 10, 2, true);
    final DBSPTypeDecimal tenFour = new DBSPTypeDecimal(CalciteObject.EMPTY, 10, 4, false);

    @Override
    public void prepareInputs(DBSPCompiler compiler) {
        String ddl = "CREATE TABLE T (\n" +
                "COL1 INT NOT NULL" +
                ", COL2 DOUBLE NOT NULL" +
                ", COL3 VARCHAR NOT NULL" +
                ", COL4 DECIMAL(10,2)" +
                ", COL5 DECIMAL(10,4) NOT NULL" +
                ");" +
                "INSERT INTO T VALUES(10, 12.0, 100100, NULL, 100103);";
        compiler.compileStatements(ddl);
    }

    public Change createInput() {
        return new Change(new DBSPZSetExpression(new DBSPTupleExpression(
                new DBSPI32Literal(10),
                new DBSPDoubleLiteral(12.0),
                new DBSPStringLiteral("100100"),
                DBSPLiteral.none(tenTwo),
                new DBSPDecimalLiteral(tenFour, new BigDecimal(100103)))));
    }

    public void testQuery(String query, DBSPZSetExpression expectedOutput) {
        query = "CREATE VIEW V AS " + query + ";";
        CompilerCircuitStream ccs = this.getCCS(query);
        InputOutputChange change = new InputOutputChange(this.createInput(), new Change(expectedOutput));
        ccs.addChange(change);
        this.addRustTestCase(ccs);
    }

    @Test
    public void testTinyInt() {
        String query = "SELECT CAST(256 AS TINYINT)";
        this.runtimeConstantFail(query, "Value '256' out of range for type");
    }

    @Test
    public void castFail() {
        this.runtimeConstantFail("SELECT CAST('blah' AS DECIMAL)",
                "Invalid decimal: unknown character");
    }

    @Test
    public void intAndString() {
        String query = "SELECT '1' + 2";
        this.testQuery(query, new DBSPZSetExpression(
                new DBSPTupleExpression(new DBSPI32Literal(3))));
    }

    @Test
    public void intAndStringTable() {
        String query = "SELECT T.COL1 + T.COL3 FROM T";
        this.testQuery(query, new DBSPZSetExpression(
                new DBSPTupleExpression(new DBSPI32Literal(100110))));
    }

    @Test
    public void castNull() {
        String query = "SELECT CAST(NULL AS INTEGER)";
        this.testQuery(query, new DBSPZSetExpression(new DBSPTupleExpression(new DBSPI32Literal())));
    }

    @Test
    public void castFromFPTest() {
        String query = "SELECT T.COL1 + T.COL2 + T.COL3 + T.COL5 FROM T";
        this.testQuery(query, new DBSPZSetExpression(new DBSPTupleExpression(new DBSPDoubleLiteral(200225.0))));
    }

    @Test
    public void decimalOutOfRange() {
        this.runtimeFail("SELECT CAST(100103123 AS DECIMAL(10, 4))",
                "cannot represent 100103123 as DECIMAL(10, 4)",
                this.streamWithEmptyChanges());
    }

    @Test
    public void testFpCasts() {
        this.testQuery("SELECT CAST(T.COL2 AS BIGINT) FROM T", new DBSPZSetExpression(new DBSPTupleExpression(new DBSPI64Literal(12))));
    }

    @Test
    public void timeCastTests() {
        this.qs("""
                SELECT CAST(1000 AS TIMESTAMP);
                 t
                ---
                 1970-01-01 00:00:01
                (1 row)
                
                SELECT CAST(3600000 AS TIMESTAMP);
                 t
                ---
                 1970-01-01 01:00:00
                (1 row)
                
                SELECT CAST(-1000 AS TIMESTAMP);
                 t
                ---
                 1969-12-31 23:59:59
                (1 row)
                
                SELECT CAST(TIMESTAMP '1970-01-01 00:00:01.234' AS INTEGER);
                 i
                ---
                 1234
                (1 row)
                
                SELECT CAST(T.COL1 * 1000 AS TIMESTAMP) FROM T;
                 t
                ---
                 1970-01-01 00:00:10
                (1 row)
                
                SELECT CAST(T.COL2 * 1000 AS TIMESTAMP) FROM T;
                 t
                ---
                 1970-01-01 00:00:12
                (1 row)
                
                SELECT CAST(CAST(T.COL2 * 1000 AS TIMESTAMP) AS INTEGER) FROM T;
                 i
                ---
                 12000
                (1 row)
                
                SELECT CAST(CAST(T.COL2 / 10 AS TIMESTAMP) AS DOUBLE) FROM T;
                 i
                ---
                 1
                (1 row)""");
    }

    @Test
    public void testFailingTimeCasts() {
        this.statementsFailingInCompilation("CREATE VIEW V AS SELECT CAST(1000 AS TIME)",
                "Cast function cannot convert value of type INTEGER to type TIME");
        this.statementsFailingInCompilation("CREATE VIEW V AS SELECT CAST(TIME '10:00:00' AS INTEGER)",
                "Cast function cannot convert value of type TIME(0) to type INTEGER");

        this.statementsFailingInCompilation("CREATE VIEW V AS SELECT CAST(1000 AS DATE)",
                "Cast function cannot convert value of type INTEGER to type DATE");
        this.statementsFailingInCompilation("CREATE VIEW V AS SELECT CAST(DATE '2024-01-01' AS INTEGER)",
                "Cast function cannot convert value of type DATE to type INTEGER");

        this.statementsFailingInCompilation("CREATE VIEW V AS SELECT CAST(X'01' AS TIME)",
                "Cast function cannot convert BINARY value to ");
    }

    @SuppressWarnings("ConstantValue")
    @Test
    public void testAllCasts() {
        String[] types = new String[] {
                "NULL",
                "BOOLEAN",
                "TINYINT",
                "SMALLINT",
                "INTEGER",
                "BIGINT",
                "DECIMAL(10, 2)",
                "REAL",
                "DOUBLE",
                "CHAR(6)",
                "VARCHAR",
                "BINARY",
                "VARBINARY",
                // long
                "INTERVAL YEARS TO MONTHS",
                "INTERVAL YEARS",
                "INTERVAL MONTHS",
                // short
                "INTERVAL DAYS",
                "INTERVAL HOURS",
                "INTERVAL DAYS TO HOURS",
                "INTERVAL MINUTES",
                "INTERVAL DAYS TO MINUTES",
                "INTERVAL HOURS TO MINUTES",
                "INTERVAL SECONDS",
                "INTERVAL DAYS TO SECONDS",
                "INTERVAL HOURS TO SECONDS",
                "INTERVAL MINUTES TO SECONDS",
                "TIME",
                "TIMESTAMP",
                "DATE",
                // "GEOMETRY",
                "ROW(lf INTEGER, rf VARCHAR)",
                "INT ARRAY",
                "MAP<INT, VARCHAR>",
                "VARIANT"
        };
        String[] values = new String[] {
                "NULL",   // NULL
                "'true'", // boolean
                "1",    // tinyint
                "1",    // smallint
                "1",    // integer
                "1",    // bigint
                "1.1",  // decimal
                "1.1e0", // real
                "1.1e0", // double
                "'chars'", // char(6)
                "'string'", // varchar
                "x'0123'", // BINARY
                "x'3456'", // VARBINARY
                "'1-2'",   // INTERVAL YEARS TO MONTHS
                "'1'",     // INTERVAL YEARS
                "'2'",     // INTERVAL MONTHS
                "'1'",     // INTERVAL DAYS",
                "'2'",     // INTERVAL HOURS",
                "'1 2'",   // INTERVAL DAYS TO HOURS",
                "'3'",     // INTERVAL MINUTES",
                "'1 2:3'", // INTERVAL DAYS TO MINUTES",
                "'2:3'",   // INTERVAL HOURS TO MINUTES",
                "'4'",     // INTERVAL SECONDS",
                "'1 2:3:4'", // INTERVAL DAYS TO SECONDS",
                "'2:3:4'", // INTERVAL HOURS TO SECONDS",
                "'3:4'",   // INTERVAL MINUTES TO SECONDS",
                "'10:00:00'",  // TIME
                "'2000-01-01 10:00:00'", // TIMESTAMP
                "'2000-01-01'", // DATE
                "ROW(1, 'string')", // ROW
                "ARRAY[1, 2, 3]",   // ARRAY
                "MAP[1, 'a', 2, 'b']", // MAP
                "1" // VARIANT
        };

        enum CanConvert {
            T, // yes
            F, // no
            N, // not implemented
        }

        final CanConvert T = CanConvert.T;
        final CanConvert F = CanConvert.F;
        final CanConvert N = CanConvert.N;

        // Rows and columns match the array of types above.
        final CanConvert[][] legal = {
          // To: N, B, I8,16,32,64,De,r, d, c, v, b, vb,ym,y, m, d, h, dh,m,dm,hm, s, ds,hs,ms,t, ts,dt,ro,a, m, V
        /*From*/
        /* N */{ F, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T },
        /* B */{ F, T, F, F, F, F, F, F, F, T, T, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, T },
        /* I8*/{ F, T, T, T, T, T, T, T, T, T, T, F, F, F, T, T, T, T, F, T, F, F, T, F, F, F, F, T, F, F, F, F, T },
        /*I16*/{ F, T, T, T, T, T, T, T, T, T, T, F, F, F, T, T, T, T, F, T, F, F, T, F, F, F, F, T, F, F, F, F, T },
        /*I32*/{ F, T, T, T, T, T, T, T, T, T, T, F, F, F, T, T, T, T, F, T, F, F, T, F, F, F, F, T, F, F, F, F, T },
        /*I64*/{ F, T, T, T, T, T, T, T, T, T, T, F, F, F, T, T, T, T, F, T, F, F, T, F, F, F, F, T, F, F, F, F, T },
        /*Dec*/{ F, T, T, T, T, T, T, T, T, T, T, F, F, F, T, T, T, T, F, T, F, F, T, F, F, F, F, T, F, F, F, F, T },
        /* r */{ F, T, T, T, T, T, T, T, T, T, T, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, T, F, F, F, F, T },
        /* d */{ F, T, T, T, T, T, T, T, T, T, T, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, T, F, F, F, F, T },
        /*chr*/{ F, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, F, F, F, T },
        /* v */{ F, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, F, F, F, T },
        /* b */{ F, F, F, F, F, F, F, F, F, T, T, T, T, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, T },
        /*vb */{ F, F, F, F, F, F, F, F, F, T, T, T, T, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, T },
        /*ym */{ F, F, F, F, F, F, F, F, F, T, T, F, F, T, T, T, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, T },
        /* y */{ F, F, T, T, T, T, T, F, F, T, T, F, F, T, T, T, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, T },
        /* m */{ F, F, T, T, T, T, T, F, F, T, T, F, F, T, T, T, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, T },
        /* d */{ F, F, T, T, T, T, T, F, F, T, T, F, F, F, F, F, T, T, T, T, T, T, T, T, T, T, F, F, F, F, F, F, T },
        /* h*/ { F, F, T, T, T, T, T, F, F, T, T, F, F, F, F, F, T, T, T, T, T, T, T, T, T, T, F, F, F, F, F, F, T },
        /* dh*/{ F, F, F, F, F, F, F, F, F, T, T, F, F, F, F, F, T, T, T, T, T, T, T, T, T, T, F, F, F, F, F, F, T },
        /* m */{ F, F, T, T, T, T, T, F, F, T, T, F, F, F, F, F, T, T, T, T, T, T, T, T, T, T, F, F, F, F, F, F, T },
        /* dm*/{ F, F, F, F, F, F, F, F, F, T, T, F, F, F, F, F, T, T, T, T, T, T, T, T, T, T, F, F, F, F, F, F, T },
        /* hm*/{ F, F, F, F, F, F, F, F, F, T, T, F, F, F, F, F, T, T, T, T, T, T, T, T, T, T, F, F, F, F, F, F, T },
        /* s */{ F, F, T, T, T, T, T, F, F, T, T, F, F, F, F, F, T, T, T, T, T, T, T, T, T, T, F, F, F, F, F, F, T },
        /* ds*/{ F, F, F, F, F, F, F, F, F, T, T, F, F, F, F, F, T, T, T, T, T, T, T, T, T, T, F, F, F, F, F, F, T },
        /* hs*/{ F, F, F, F, F, F, F, F, F, T, T, F, F, F, F, F, T, T, T, T, T, T, T, T, T, T, F, F, F, F, F, F, T },
        /* ms*/{ F, F, F, F, F, F, F, F, F, T, T, F, F, F, F, F, T, T, T, T, T, T, T, T, T, T, F, F, F, F, F, F, T },
        /* t */{ F, F, F, F, F, F, F, F, F, T, T, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, T, T, F, F, F, F, T },
        /* ts*/{ F, F, T, T, T, T, T, T, T, T, T, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, T, T, T, F, F, F, T },
        /* dt*/{ F, F, F, F, F, F, F, F, F, T, T, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, T, T, F, F, F, T },
        /*row*/{ F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, T, F, F, T },
        /* a */{ F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, T, F, T },
        /* m */{ F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, T, T },
        /* V */{ F, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T },
        };

        assert types.length == legal.length;
        assert types.length == legal[0].length;
        StringBuilder program = new StringBuilder();
        program.append("CREATE VIEW V AS SELECT ");
        boolean first = true;
        for (int i = 0; i < types.length; i++) {
            String type = types[i];
            String value = values[i];
            if (legal[i][i] == CanConvert.F)
                continue;
            if (!first)
                program.append(", ");
            first = false;
            program.append("CAST(").append(value).append(" AS ").append(type).append(")\n");
        }
        program.append(";\n");

        for (int i = 0; i < types.length; i++) {
            String value = values[i];
            String from = types[i];
            if (!Linq.any(legal[i], p -> p == T)) continue;
            program.append("CREATE VIEW V").append(i).append(" AS SELECT ");

            first = true;
            for (int j = 0; j < types.length; j++) {
                String to = types[j];
                CanConvert ok = legal[i][j];
                if (ok == CanConvert.F) {
                    if (!value.equals("NULL") && !from.equals("NULL") && !to.equals("NULL")) {
                        String statement = "CREATE VIEW V AS SELECT CAST(CAST(" + value + " AS " + from + ") AS " + to + ")";
                        this.statementsFailingInCompilation(statement, "Cast function cannot convert");
                    }
                    continue;
                }
                if (!first)
                    program.append(", ");
                first = false;
                program.append("CAST(");
                if (value.equals("NULL"))
                    // Special case
                    program.append(value);
                else
                    program.append("CAST(").append(value).append(" AS ").append(from).append(")");
                program.append(" AS ").append(to).append(")");
                program.append("\n");
            }
            program.append(";\n");
        }
        this.compileRustTestCase(program.toString());
    }
}
