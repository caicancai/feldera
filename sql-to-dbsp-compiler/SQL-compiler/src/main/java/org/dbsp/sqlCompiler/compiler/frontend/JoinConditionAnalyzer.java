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

package org.dbsp.sqlCompiler.compiler.frontend;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rex.*;
import org.apache.calcite.sql.SqlKind;
import org.dbsp.sqlCompiler.compiler.errors.CompilationError;
import org.dbsp.sqlCompiler.compiler.errors.InternalCompilerError;
import org.dbsp.sqlCompiler.compiler.errors.UnimplementedException;
import org.dbsp.sqlCompiler.compiler.frontend.calciteObject.CalciteObject;
import org.dbsp.sqlCompiler.ir.type.DBSPType;
import org.dbsp.sqlCompiler.ir.type.derived.DBSPTypeStruct;
import org.dbsp.sqlCompiler.ir.type.derived.DBSPTypeTuple;
import org.dbsp.sqlCompiler.ir.type.derived.DBSPTypeTupleBase;
import org.dbsp.util.IWritesLogs;
import org.dbsp.util.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class JoinConditionAnalyzer implements IWritesLogs {
    private final int leftTableColumnCount;
    private final TypeCompiler typeCompiler;

    public JoinConditionAnalyzer(int leftTableColumnCount, TypeCompiler typeCompiler) {
        this.leftTableColumnCount = leftTableColumnCount;
        this.typeCompiler = typeCompiler;
    }

    /** Represents an equality test in a join between two columns in the two tables.
     * @param leftColumn  Column from the left relation that is compared for equality
     * @param rightColumn Column from the right relation that is compared for equality
     * @param commonType  Type that columns have to be cast to
     * @param nonNull     If true the columns cannot be null */
    record EqualityTest(int leftColumn, int rightColumn, DBSPType commonType, boolean nonNull) {
        EqualityTest(int leftColumn, int rightColumn, DBSPType commonType, boolean nonNull) {
            this.leftColumn = leftColumn;
            this.rightColumn = rightColumn;
            this.commonType = commonType;
            this.nonNull = nonNull;
            if (leftColumn < 0 || rightColumn < 0)
                throw new InternalCompilerError("Illegal column number " +
                        leftColumn + ":" + rightColumn, commonType);
        }

        public EqualityTest withType(DBSPType type) {
            return new EqualityTest(this.leftColumn, this.rightColumn, type, this.nonNull);
        }
    }

    /**
     * A join condition is decomposed into a list of equality comparisons
     * and another general-purpose boolean expression. */
    class ConditionDecomposition {
        final RelNode join;
        public final List<EqualityTest> comparisons;
        @Nullable
        RexNode leftOver;

        ConditionDecomposition(RelNode join) {
            this.join = join;
            this.comparisons = new ArrayList<>();
            this.leftOver = null;
        }

        void setLeftOver(RexNode leftOver) {
            this.leftOver = leftOver;
        }

        public void addEquality(RexNode left, RexNode right, DBSPType commonType, boolean nonNull) {
            RexInputRef ref = Objects.requireNonNull(asInputRef(left));
            int l = ref.getIndex();
            ref = Objects.requireNonNull(asInputRef(right));
            int r = ref.getIndex() - JoinConditionAnalyzer.this.leftTableColumnCount;
            this.comparisons.add(new EqualityTest(l, r, commonType, nonNull));
        }

        public boolean isCrossJoin() {
            return this.comparisons.isEmpty() && this.leftOver == null;
        }

        void validate() {
            if (this.leftOver == null && this.comparisons.isEmpty())
                throw new InternalCompilerError("Unexpected empty join condition",
                        CalciteObject.create(this.join));
        }

        /** Part of the join condition that is not an equality test.
         * @return Null if the entire condition is an equality test. */
        @Nullable
        public RexNode getLeftOver() {
            this.validate();
            return this.leftOver;
        }

        void analyzeAnd(RexCall call) {
            List<RexNode> operands = call.getOperands();
            List<RexNode> unprocessed = new ArrayList<>();
            for (int i = 0; i < operands.size(); i++) {
                RexNode operand = call.operands.get(i);
                if (!(operand instanceof RexCall opCall)) {
                    unprocessed.add(operand);
                    continue;
                }

                if (opCall.op.kind != SqlKind.EQUALS &&
                        opCall.op.kind != SqlKind.IS_NOT_DISTINCT_FROM) {
                    unprocessed.add(operand);
                    continue;
                }

                boolean eq = this.analyzeEquals(opCall);
                if (!eq) {
                    unprocessed.add(opCall);
                }
            }

            if (!unprocessed.isEmpty()) {
                if (unprocessed.size() == 1) {
                    this.setLeftOver(unprocessed.get(0));
                } else {
                    call = call.clone(call.type, unprocessed);
                    this.setLeftOver(call);
                }
            }
        }

        /** Analyze an equality comparison.  Return 'true' if this is suitable for an equijoin */
        public boolean analyzeEquals(RexCall call) {
            assert call.operands.size() == 2: "Expected 2 operands for equality checking";
            RexNode left = call.operands.get(0);
            RexNode right = call.operands.get(1);
            @Nullable
            Boolean leftIsLeft = JoinConditionAnalyzer.this.isLeftTableColumnReference(left);
            @Nullable
            Boolean rightIsLeft = JoinConditionAnalyzer.this.isLeftTableColumnReference(right);
            if (leftIsLeft == null || rightIsLeft == null) {
                return false;
            }
            if (leftIsLeft == rightIsLeft) {
                // Both columns refer to the same table.
                return false;
            }
            DBSPType leftType = JoinConditionAnalyzer.this.typeCompiler.convertType(
                    left.getType(), true);
            DBSPType rightType = JoinConditionAnalyzer.this.typeCompiler.convertType(
                    right.getType(), true);
            boolean mayBeNull = false;
            if (call.op.kind == SqlKind.IS_NOT_DISTINCT_FROM) {
                // Only used if any of the operands is not nullable
                if (leftType.mayBeNull && rightType.mayBeNull) {
                    mayBeNull = true;
                }
            }
            if (leftType.is(DBSPTypeTupleBase.class) || leftType.is(DBSPTypeStruct.class))
                throw new UnimplementedException("Join on struct types",
                        3398, CalciteObject.create(this.join, call));
            DBSPType commonType = TypeCompiler.reduceType(
                    CalciteObject.create(this.join, call),
                    leftType, rightType, "Consider using an INNER JOIN with an explicit ON condition.\n" +
                            "In NATURAL or USING JOIN: ").withMayBeNull(mayBeNull);
            if (leftIsLeft) {
                this.addEquality(left, right, commonType, !mayBeNull);
            } else {
                this.addEquality(right, left, commonType, !mayBeNull);
            }
            return true;
        }
    }

    @Nullable
    public static RexInputRef asInputRef(RexNode node) {
        if (!(node instanceof RexInputRef))
            return null;
        return (RexInputRef) node;
    }

    /**
     * Returns 'true' if this expression is referencing a column in the left table.
     * @param node  A row expression.
     * @return null if this does not refer to a table column. */
    @Nullable
    public Boolean isLeftTableColumnReference(RexNode node) {
        RexInputRef ref = asInputRef(node);
        if (ref == null)
            return null;
        return ref.getIndex() < this.leftTableColumnCount;
    }

    JoinConditionAnalyzer.ConditionDecomposition analyze(RelNode context, RexNode expression) {
        Logger.INSTANCE.belowLevel(this, 1)
                .append("Analyzing ")
                .appendSupplier(expression::toString)
                .newline();
        final ConditionDecomposition result = new ConditionDecomposition(context);
        if (! (expression instanceof RexCall call)) {
            result.setLeftOver(expression);
            return result;
        }
        if (call.op.kind == SqlKind.AND) {
            result.analyzeAnd(call);
        } else if (call.op.kind == SqlKind.EQUALS || call.op.kind == SqlKind.IS_NOT_DISTINCT_FROM) {
            boolean success = result.analyzeEquals(call);
            if (!success) {
                result.setLeftOver(call);
            }
        } else {
            result.setLeftOver(call);
        }
        return result;
    }
}
