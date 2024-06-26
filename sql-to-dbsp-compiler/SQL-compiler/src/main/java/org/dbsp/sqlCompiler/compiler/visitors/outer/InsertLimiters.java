package org.dbsp.sqlCompiler.compiler.visitors.outer;

import org.dbsp.sqlCompiler.circuit.DBSPCircuit;
import org.dbsp.sqlCompiler.circuit.operator.DBSPAggregateOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPApplyOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPControlledFilterOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPDeindexOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPDelayOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPFilterOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPIndexOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPIntegrateTraceRetainKeysOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPJoinOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPMapOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPSourceMultisetOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPViewOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPWaterlineOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPWindowOperator;
import org.dbsp.sqlCompiler.compiler.IErrorReporter;
import org.dbsp.sqlCompiler.compiler.IHasColumnsMetadata;
import org.dbsp.sqlCompiler.compiler.IHasLateness;
import org.dbsp.sqlCompiler.compiler.IHasWatermark;
import org.dbsp.sqlCompiler.compiler.errors.UnimplementedException;
import org.dbsp.sqlCompiler.compiler.frontend.calciteObject.CalciteObject;
import org.dbsp.sqlCompiler.compiler.visitors.inner.monotone.IMaybeMonotoneType;
import org.dbsp.sqlCompiler.compiler.visitors.inner.monotone.MonotoneExpression;
import org.dbsp.sqlCompiler.compiler.visitors.inner.monotone.PartiallyMonotoneTuple;
import org.dbsp.sqlCompiler.compiler.visitors.outer.expansion.AggregateExpansion;
import org.dbsp.sqlCompiler.compiler.visitors.outer.expansion.JoinExpansion;
import org.dbsp.sqlCompiler.compiler.visitors.outer.expansion.OperatorExpansion;
import org.dbsp.sqlCompiler.ir.DBSPParameter;
import org.dbsp.sqlCompiler.ir.expression.DBSPBinaryExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPClosureExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPOpcode;
import org.dbsp.sqlCompiler.ir.expression.DBSPRawTupleExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPTupleExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPVariablePath;
import org.dbsp.sqlCompiler.ir.type.DBSPType;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeCode;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeIndexedZSet;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeUser;
import org.dbsp.sqlCompiler.ir.type.IsBoundedType;
import org.dbsp.util.Linq;
import org.dbsp.util.Logger;
import org.dbsp.util.Utilities;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** As a result of the Monotonicity analysis, this pass inserts 4 types of new operators:
 * - ControlledFilter operators to throw away tuples that are not "useful"
 * - apply operators that compute the bounds that drive the controlled filters
 * - waterline operators near sources with lateness information
 * - DBSPIntegrateTraceRetainKeysOperator to prune data from integral operators
 **/
public class InsertLimiters extends CircuitCloneVisitor {
    /** For each operator in the expansion of the operators of this circuit
     * the list of its monotone output columns */
    public final Map<DBSPOperator, MonotoneExpression> expansionMonotoneValues;
    /** Circuit that contains the expansion of the circuit we are modifying */
    public final DBSPCircuit expandedCircuit;
    /** Maps each original operator to the set of operators it was expanded to */
    public final Map<DBSPOperator, OperatorExpansion> expandedInto;
    /** Maps each operator to the one that computes its lower bound.
     * The keys in this map can be both operators from this circuit and from
     * the expanded circuit. */
    public final Map<DBSPOperator, DBSPOperator> bound;

    public InsertLimiters(IErrorReporter reporter,
                          DBSPCircuit expandedCircuit,
                          Map<DBSPOperator, MonotoneExpression> expansionMonotoneValues,
                          Map<DBSPOperator, OperatorExpansion> expandedInto) {
        super(reporter, false);
        this.expandedCircuit = expandedCircuit;
        this.expansionMonotoneValues = expansionMonotoneValues;
        this.expandedInto = expandedInto;
        this.bound = new HashMap<>();
    }

    void markBound(DBSPOperator operator, DBSPOperator bound) {
        Logger.INSTANCE.belowLevel(this, 2)
                .append("Bound for ")
                .append(operator.getIdString())
                .append(" computed by ")
                .append(bound.getIdString())
                .newline();
        Utilities.putNew(this.bound, operator, bound);
    }

    /**
     * @param operatorFromExpansion Operator produced as the expansion of
     *                              another operator.
     * @param input                 Input of the operatorFromExpansion which
     *                              is used.
     * @return Add an operator which computes the smallest legal value
     * for the output of an operator. */
    @Nullable
    DBSPOperator addBounds(@Nullable DBSPOperator operatorFromExpansion, int input) {
        if (operatorFromExpansion == null)
            return null;
        MonotoneExpression monotone = this.expansionMonotoneValues.get(operatorFromExpansion);
        if (monotone == null)
            return null;
        DBSPOperator source = operatorFromExpansion.inputs.get(input);  // Even for binary operators
        DBSPOperator boundSource = Utilities.getExists(this.bound, source);
        DBSPClosureExpression function = monotone.getReducedExpression().to(DBSPClosureExpression.class);
        DBSPOperator bound = new DBSPApplyOperator(operatorFromExpansion.getNode(), function,
                function.getFunctionType().resultType, boundSource,
                "(" + operatorFromExpansion.getDerivedFrom() + ")");
        this.getResult().addOperator(bound);  // insert directly into circuit
        this.markBound(operatorFromExpansion, bound);
        return bound;
    }

    @Override
    public void postorder(DBSPMapOperator operator) {
        this.addBounds(operator, 0);
        super.postorder(operator);
    }

    @Override
    public void postorder(DBSPFilterOperator operator) {
        this.addBounds(operator, 0);
        super.postorder(operator);
    }

    @Override
    public void postorder(DBSPIndexOperator operator) {
        this.addBounds(operator, 0);
        super.postorder(operator);
    }

    @Override
    public void postorder(DBSPAggregateOperator aggregator) {
        DBSPOperator source = this.mapped(aggregator.input());
        OperatorExpansion expanded = this.expandedInto.get(aggregator);
        if (expanded == null) {
            super.postorder(aggregator);
            return;
        }

        AggregateExpansion ae = expanded.to(AggregateExpansion.class);
        DBSPOperator limiter = this.addBounds(ae.integrator, 0);
        if (limiter == null) {
            super.postorder(aggregator);
            return;
        }

        MonotoneExpression expression = this.expansionMonotoneValues.get(ae.integrator);
        DBSPOperator filteredAggregator;
        if (false) {
            DBSPControlledFilterOperator filter =
                    DBSPControlledFilterOperator.create(
                            aggregator.getNode(), source, Monotonicity.getBodyType(expression), limiter);
            this.addOperator(filter);
            filteredAggregator = aggregator.withInputs(Linq.list(filter), false);
        } else {
            filteredAggregator = aggregator.withInputs(Linq.list(source), false);
        }

        // We use the input 1, coming from the integrator
        DBSPOperator limiter2 = this.addBounds(ae.aggregator, 1);
        if (limiter2 == null) {
            this.map(aggregator, filteredAggregator);
            return;
        }

        this.addOperator(filteredAggregator);
        MonotoneExpression monotoneValue2 = this.expansionMonotoneValues.get(ae.aggregator);
        IMaybeMonotoneType projection2 = Monotonicity.getBodyType(monotoneValue2);
        // A second controlled filter for the output of the aggregator
        if (false) {
            DBSPOperator filter2 = DBSPControlledFilterOperator.create(
                    aggregator.getNode(), filteredAggregator, projection2, limiter2);
            this.markBound(aggregator, filter2);
            this.map(aggregator, filter2);
        } else {
            // The before and after filters are actually identical for now.
            DBSPIntegrateTraceRetainKeysOperator before = DBSPIntegrateTraceRetainKeysOperator.create(
                    aggregator.getNode(), source, projection2, limiter2);
            this.addOperator(before);
            // output of 'before' is never used

            DBSPIntegrateTraceRetainKeysOperator after = DBSPIntegrateTraceRetainKeysOperator.create(
                    aggregator.getNode(), filteredAggregator, projection2, limiter2);
            this.addOperator(after);
            // output of 'after'' is never used

            this.map(aggregator, filteredAggregator, false);
        }
    }

    @Override
    public void postorder(DBSPJoinOperator join) {
        DBSPOperator left = this.mapped(join.inputs.get(0));
        DBSPOperator right = this.mapped(join.inputs.get(1));
        OperatorExpansion expanded = this.expandedInto.get(join);
        if (expanded == null) {
            super.postorder(join);
            return;
        }

        JoinExpansion je = expanded.to(JoinExpansion.class);
        DBSPOperator leftLimiter = this.addBounds(je.left, 0);
        DBSPOperator rightLimiter = this.addBounds(je.right, 0);
        if (leftLimiter == null && rightLimiter == null) {
            super.postorder(join);
            return;
        }

        DBSPOperator result = join.withInputs(Linq.list(left, right), false);
        if (leftLimiter != null) {
            MonotoneExpression leftMonotone = this.expansionMonotoneValues.get(je.left);
            // Yes, the limit of the left input is applied to the right one.
            IMaybeMonotoneType leftProjection = Monotonicity.getBodyType(leftMonotone);
            // Check if the "key" field is monotone
            if (leftProjection.to(PartiallyMonotoneTuple.class).getField(0).mayBeMonotone()) {
                DBSPIntegrateTraceRetainKeysOperator r = DBSPIntegrateTraceRetainKeysOperator.create(
                        join.getNode(), right, leftProjection, leftLimiter);
                this.addOperator(r);
            }
        }

        if (rightLimiter != null) {
            MonotoneExpression rightMonotone = this.expansionMonotoneValues.get(je.right);
            // Yes, the limit of the right input is applied to the left one.
            IMaybeMonotoneType rightProjection = Monotonicity.getBodyType(rightMonotone);
            // Check if the "key" field is monotone
            if (rightProjection.to(PartiallyMonotoneTuple.class).getField(0).mayBeMonotone()) {
                DBSPIntegrateTraceRetainKeysOperator l = DBSPIntegrateTraceRetainKeysOperator.create(
                        join.getNode(), left, rightProjection, rightLimiter);
                this.addOperator(l);
            }
        }

        this.map(join, result, true);
    }

    /** Process LATENESS annotations.
     * @return Return the original operator if there aren't any annotations, or
     * the operator that produces the result of the input filtered otherwise. */
    DBSPOperator processLateness(DBSPOperator operator) {
        MonotoneExpression expression = this.expansionMonotoneValues.get(operator);
        if (expression == null) {
            return operator;
        }
        List<DBSPExpression> bounds = new ArrayList<>();
        List<DBSPExpression> minimums = new ArrayList<>();
        int index = 0;
        DBSPVariablePath t = new DBSPVariablePath("t", operator.getOutputZSetType().elementType.ref());
        for (IHasLateness column: operator.to(IHasColumnsMetadata.class).getLateness()) {
            DBSPExpression lateness = column.getLateness();
            if (lateness != null) {
                DBSPExpression field = t.deref().field(index);
                DBSPType type = field.getType();
                field = new DBSPBinaryExpression(operator.getNode(), field.getType(),
                        DBSPOpcode.SUB, field, lateness);
                bounds.add(field);
                DBSPExpression min = type.to(IsBoundedType.class).getMinValue();
                minimums.add(min);
            }
            index++;
        }
        if (minimums.isEmpty())
            return operator;

        // The waterline operator will compute the *minimum legal value* of all the
        // inputs that have a lateness attached.  The output signature contains only
        // the columns that have lateness.
        this.addOperator(operator);
        DBSPTupleExpression min = new DBSPTupleExpression(minimums, false);
        DBSPTupleExpression bound = new DBSPTupleExpression(bounds, false);
        DBSPParameter parameter = t.asParameter();
        DBSPWaterlineOperator waterline = new DBSPWaterlineOperator(
                operator.getNode(), min.closure(), bound.closure(parameter), operator);
        this.addOperator(waterline);
        this.markBound(operator, waterline);

        // Waterline fed through a delay
        DBSPDelayOperator delay = new DBSPDelayOperator(operator.getNode(), min, waterline);
        this.addOperator(delay);
        this.markBound(delay, waterline);
        return DBSPControlledFilterOperator.create(
                operator.getNode(), operator, Monotonicity.getBodyType(expression), delay);
    }

    @Override
    public void postorder(DBSPSourceMultisetOperator operator) {
        DBSPOperator replacement = this.processLateness(operator);

        // Process watermark annotations.  Very similar to lateness anotations.
        int index = 0;
        DBSPType dataType = operator.getOutputZSetType().elementType;
        DBSPVariablePath t = new DBSPVariablePath("t", dataType.ref());
        List<DBSPExpression> fields = new ArrayList<>();
        List<DBSPExpression> bounds = new ArrayList<>();
        List<DBSPExpression> minimums = new ArrayList<>();
        for (IHasWatermark column: operator.to(IHasColumnsMetadata.class).getWatermarks()) {
            DBSPExpression lateness = column.getWatermark();

            if (lateness != null) {
                DBSPExpression field = t.deref().field(index);
                fields.add(field);
                DBSPType type = field.getType();
                field = new DBSPBinaryExpression(operator.getNode(), field.getType(),
                        DBSPOpcode.SUB, field.deepCopy(), lateness);
                bounds.add(field);
                DBSPExpression min = type.to(IsBoundedType.class).getMinValue();
                minimums.add(min);
            }
            index++;
        }

        // Currently we only support at most 1 watermark column per table.
        // TODO: support multiple fields.
        if (minimums.size() > 1) {
            throw new UnimplementedException("More than 1 watermark per table not yet supported", operator.getNode());
        }

        if (!minimums.isEmpty()) {
            assert fields.size() == 1;
            this.addOperator(replacement);

            DBSPTupleExpression min = new DBSPTupleExpression(minimums, false);
            DBSPTupleExpression bound = new DBSPTupleExpression(bounds, false);
            DBSPParameter parameter = t.asParameter();
            DBSPWaterlineOperator waterline = new DBSPWaterlineOperator(
                    operator.getNode(), min.closure(), bound.closure(parameter), operator);
            this.addOperator(waterline);

            DBSPType windowBoundType = fields.get(0).getType();
            DBSPType dynData = new DBSPTypeUser(CalciteObject.EMPTY, DBSPTypeCode.USER, "DynData", false);
            DBSPTypeUser typedBox = new DBSPTypeUser(CalciteObject.EMPTY, DBSPTypeCode.USER,
                    "TypedBox", false, windowBoundType, dynData);

            DBSPVariablePath var = new DBSPVariablePath("t", bound.getType().ref());
            DBSPExpression makePair = new DBSPRawTupleExpression(
                    typedBox.constructor(minimums.get(0)),
                    typedBox.constructor(var.deref().field(0)));
            DBSPApplyOperator apply = new DBSPApplyOperator(
                    operator.getNode(), makePair.closure(var.asParameter()), makePair.getType(), waterline, null);
            this.addOperator(apply);

            // Window requires data to be indexed
            DBSPIndexOperator ix = new DBSPIndexOperator(operator.getNode(),
                    new DBSPRawTupleExpression(fields.get(0), t.deref()).closure(t.asParameter()),
                    new DBSPTypeIndexedZSet(operator.getNode(),
                            fields.get(0).getType(), dataType), true, replacement);
            this.addOperator(ix);
            DBSPWindowOperator window = new DBSPWindowOperator(operator.getNode(), ix, apply);
            this.addOperator(window);
            replacement = new DBSPDeindexOperator(operator.getNode(), window);
        }

        if (replacement == operator) {
            this.replace(operator);
        } else {
            this.map(operator, replacement);
        }
    }

    @Override
    public void postorder(DBSPViewOperator operator) {
        if (operator.hasLateness()) {
            // Treat like a source operator
            DBSPOperator replacement = this.processLateness(operator);
            if (replacement == operator) {
                this.replace(operator);
            } else {
                this.map(operator, replacement);
            }
        } else {
            // Treat like an identity function
            this.addBounds(operator, 0);
            super.postorder(operator);
        }
    }
}
