package org.dbsp.sqlCompiler.circuit.operator;

import org.dbsp.sqlCompiler.circuit.OutputPort;
import org.dbsp.sqlCompiler.compiler.frontend.calciteObject.CalciteObject;
import org.dbsp.sqlCompiler.compiler.visitors.VisitDecision;
import org.dbsp.sqlCompiler.compiler.visitors.outer.CircuitVisitor;
import org.dbsp.sqlCompiler.ir.NonCoreIR;
import org.dbsp.sqlCompiler.ir.expression.DBSPExpression;
import org.dbsp.sqlCompiler.ir.type.DBSPType;

import javax.annotation.Nullable;
import java.util.List;

/** This is a primitive operator that corresponds to the Rust AggregateIncremental node. */
@NonCoreIR
public final class DBSPPrimitiveAggregateOperator extends DBSPBinaryOperator {
    public DBSPPrimitiveAggregateOperator(
            CalciteObject node, @Nullable DBSPExpression function, DBSPType outputType,
            OutputPort delta, OutputPort integral) {
        super(node, "AggregateIncremental", function, outputType, false, delta, integral);
        assert delta.getOutputIndexedZSetType().sameType(integral.getOutputIndexedZSetType());
    }

    @Override
    public DBSPSimpleOperator withFunction(@Nullable DBSPExpression expression, DBSPType outputType) {
        return new DBSPPrimitiveAggregateOperator(this.getNode(), expression,
                outputType, this.left(), this.right()).copyAnnotations(this);
    }

    @Override
    public DBSPSimpleOperator withInputs(List<OutputPort> newInputs, boolean force) {
        assert newInputs.size() == 2: "Expected 2 inputs";
        if (force || this.inputsDiffer(newInputs))
            return new DBSPPrimitiveAggregateOperator(this.getNode(), this.function,
                    this.outputType, newInputs.get(0), newInputs.get(1)).copyAnnotations(this);
        return this;
    }

    @Override
    public void accept(CircuitVisitor visitor) {
        visitor.push(this);
        VisitDecision decision = visitor.preorder(this);
        if (!decision.stop())
            visitor.postorder(this);
        visitor.pop(this);
    }
}
