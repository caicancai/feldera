package org.dbsp.sqlCompiler.circuit.operator;

import org.dbsp.sqlCompiler.circuit.OutputPort;
import org.dbsp.sqlCompiler.compiler.TableMetadata;
import org.dbsp.sqlCompiler.compiler.frontend.calciteCompiler.ProgramIdentifier;
import org.dbsp.sqlCompiler.compiler.frontend.calciteObject.CalciteObject;
import org.dbsp.sqlCompiler.compiler.visitors.VisitDecision;
import org.dbsp.sqlCompiler.compiler.visitors.outer.CircuitVisitor;
import org.dbsp.sqlCompiler.ir.expression.DBSPExpression;
import org.dbsp.sqlCompiler.ir.type.DBSPType;
import org.dbsp.sqlCompiler.ir.type.derived.DBSPTypeStruct;
import org.dbsp.sqlCompiler.ir.type.derived.DBSPTypeTuple;
import org.dbsp.sqlCompiler.ir.type.user.DBSPTypeZSet;

import javax.annotation.Nullable;
import java.util.List;

public final class DBSPSourceMultisetOperator
        extends DBSPSourceTableOperator {
    /**
     * Create a DBSP operator that is a source to the dataflow graph.
     * The table has *no* primary key, so the data can form a multiset.
     *
     * @param node       Calcite node for the statement creating the table
     *                   that this node is created from.
     * @param sourceName Calcite node for the identifier naming the table.
     * @param outputType Type of table.
     * @param name       The name of the table that this operator is created from.
     * @param comment    A comment describing the operator. */
    public DBSPSourceMultisetOperator(
            CalciteObject node, CalciteObject sourceName,
            DBSPTypeZSet outputType, DBSPTypeStruct originalRowType,
            TableMetadata metadata, ProgramIdentifier name, @Nullable String comment) {
        super(node, "multiset", sourceName, outputType, originalRowType, true, metadata, name, comment);
        assert metadata.getColumnCount() == originalRowType.fields.size();
        assert metadata.getColumnCount() == outputType.elementType.to(DBSPTypeTuple.class).size();
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
    public DBSPSimpleOperator withFunction(@Nullable DBSPExpression unused, DBSPType outputType) {
        return new DBSPSourceMultisetOperator(this.getNode(), this.sourceName,
                outputType.to(DBSPTypeZSet.class), this.originalRowType,
                this.metadata, this.tableName, this.comment).copyAnnotations(this);
    }

    @Override
    public DBSPSimpleOperator withInputs(List<OutputPort> newInputs, boolean force) {
        assert newInputs.isEmpty();
        if (force)
            return new DBSPSourceMultisetOperator(
                    this.getNode(), this.sourceName, this.getOutputZSetType(), this.originalRowType,
                    this.metadata, this.tableName, this.comment).copyAnnotations(this);
        return this;
    }

    @Override
    public String toString() {
        return this.getClass()
                .getSimpleName()
                .replace("DBSP", "")
                .replace("Operator", "")
                + " " + this.tableName
                + " " + this.getIdString();
    }
}
