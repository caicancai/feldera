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
import org.dbsp.sqlCompiler.compiler.errors.UnimplementedException;
import org.dbsp.sqlCompiler.compiler.frontend.calciteObject.CalciteObject;
import org.dbsp.sqlCompiler.compiler.visitors.VisitDecision;
import org.dbsp.sqlCompiler.compiler.visitors.outer.CircuitVisitor;
import org.dbsp.sqlCompiler.ir.expression.DBSPClosureExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPRawTupleExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPVariablePath;
import org.dbsp.sqlCompiler.ir.type.DBSPType;
import org.dbsp.sqlCompiler.ir.type.user.DBSPTypeIndexedZSet;
import org.dbsp.sqlCompiler.ir.type.user.DBSPTypeZSet;

import javax.annotation.Nullable;
import java.util.List;

/** Same as a map with identity function, but, unlike a {@link DBSPMapOperator},
 * the output can be an IndexedZSet. */
public final class DBSPNoopOperator extends DBSPUnaryOperator {
    static DBSPClosureExpression getClosure(DBSPType sourceType) {
        if (sourceType.is(DBSPTypeZSet.class)) {
            DBSPVariablePath var = sourceType.to(DBSPTypeZSet.class).elementType.ref().var();
            return var.deref().applyClone().closure(var);
        } else if (sourceType.is(DBSPTypeIndexedZSet.class)) {
            DBSPTypeIndexedZSet ix = sourceType.to(DBSPTypeIndexedZSet.class);
            DBSPVariablePath var = ix.getKVRefType().var();
            return new DBSPRawTupleExpression(
                    var.field(0).deref().applyClone(),
                    var.field(1).deref().applyClone())
                    .closure(var);
        } else {
            throw new UnimplementedException("Noop for type " + sourceType);
        }
    }

    public DBSPNoopOperator(CalciteObject node, OutputPort source,
                            @Nullable String comment) {
        super(node, "noop", getClosure(source.outputType()),
                source.outputType(), source.isMultiset(), source, comment);
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
    public DBSPSimpleOperator withInputs(List<OutputPort> newInputs, boolean force) {
        if (force || this.inputsDiffer(newInputs))
            return new DBSPNoopOperator(this.getNode(), newInputs.get(0), this.comment)
                    .copyAnnotations(this);
        return this;
    }

    // equivalent inherited from base class
}
