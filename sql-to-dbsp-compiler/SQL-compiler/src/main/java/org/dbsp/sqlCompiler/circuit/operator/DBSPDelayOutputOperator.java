package org.dbsp.sqlCompiler.circuit.operator;

import org.dbsp.sqlCompiler.compiler.frontend.calciteObject.CalciteObject;
import org.dbsp.sqlCompiler.compiler.visitors.VisitDecision;
import org.dbsp.sqlCompiler.compiler.visitors.outer.CircuitVisitor;
import org.dbsp.sqlCompiler.ir.NonCoreIR;
import org.dbsp.sqlCompiler.ir.expression.DBSPExpression;
import org.dbsp.sqlCompiler.ir.type.DBSPType;
import org.dbsp.util.NameGen;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The second half of a delay operator.
 * This operator does not have an explicit input; the input is coming implicitly from
 * the corresponding DBSPDelayOperator.  This trick makes all graphs look acyclic.
 * This looks like a source operator, but it has a "hidden" input.
 * The ToDot visitor will correct this situation when drawing them. */
@NonCoreIR
public final class DBSPDelayOutputOperator extends DBSPSourceBaseOperator {
    public DBSPDelayOutputOperator(CalciteObject node, DBSPType outputType, boolean isMultiset,
                                   @Nullable String comment) {
        super(node, "delay_out", outputType, isMultiset, new NameGen("delay").nextName(), comment);
    }

    @Override
    public DBSPOperator withFunction(@Nullable DBSPExpression expression, DBSPType outputType) {
        return new DBSPDelayOutputOperator(this.getNode(), outputType, this.isMultiset, this.comment)
                .copyAnnotations(this);
    }

    @Override
    public DBSPOperator withInputs(List<DBSPOperator> newInputs, boolean force) {
        if (force || this.inputsDiffer(newInputs))
            return new DBSPDelayOutputOperator(this.getNode(), this.outputType, this.isMultiset, this.comment)
                    .copyAnnotations(this);
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
