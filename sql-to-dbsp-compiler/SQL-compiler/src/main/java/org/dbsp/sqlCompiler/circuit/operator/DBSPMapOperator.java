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

package org.dbsp.sqlCompiler.circuit.operator;

import org.dbsp.sqlCompiler.circuit.OutputPort;
import org.dbsp.sqlCompiler.compiler.frontend.calciteObject.CalciteObject;
import org.dbsp.sqlCompiler.compiler.visitors.VisitDecision;
import org.dbsp.sqlCompiler.compiler.visitors.outer.CircuitVisitor;
import org.dbsp.sqlCompiler.ir.expression.DBSPClosureExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPExpression;
import org.dbsp.sqlCompiler.ir.type.DBSPType;
import org.dbsp.sqlCompiler.ir.type.user.DBSPTypeZSet;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

public final class DBSPMapOperator extends DBSPUnaryOperator {
    public DBSPMapOperator(CalciteObject node, DBSPExpression function,
                           DBSPTypeZSet outputType, OutputPort input) {
        // Currently the output type can only be a ZSet, but the input
        // type may be a ZSet or an IndexedZSet.
        super(node, "map", function, outputType, true, input);
        DBSPType elementType = this.getOutputZSetElementType();
        if (function.is(DBSPClosureExpression.class))
            // Could also be a SortExpression
            this.checkParameterCount(function,  1);
        this.checkResultType(function, elementType);
        this.checkArgumentFunctionType(function, 0, input);
    }

    public DBSPMapOperator(CalciteObject node, DBSPClosureExpression function, OutputPort input) {
        this(node, function, new DBSPTypeZSet(function.getResultType()), input);
    }

    @Override
    public void accept(CircuitVisitor visitor) {
        visitor.push(this);
        VisitDecision decision = visitor.preorder(this);
        if (!decision.stop())
            visitor.postorder(this);
        visitor.pop(this);
    }

    @Override
    public DBSPSimpleOperator withFunction(@Nullable DBSPExpression expression, DBSPType outputType) {
        return new DBSPMapOperator(
                this.getNode(), Objects.requireNonNull(expression),
                outputType.to(DBSPTypeZSet.class), this.input())
                .copyAnnotations(this);
    }

    @Override
    public DBSPSimpleOperator withInputs(List<OutputPort> newInputs, boolean force) {
        assert newInputs.size() == 1;
        if (force || this.inputsDiffer(newInputs))
            return new DBSPMapOperator(
                    this.getNode(), this.getFunction(),
                    this.getOutputZSetType(), newInputs.get(0))
                    .copyAnnotations(this);
        return this;
    }

    // equivalent inherited from base class
}
