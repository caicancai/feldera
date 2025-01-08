package org.dbsp.sqlCompiler.compiler.frontend.calciteCompiler;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperandCountRange;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.sql.fun.SqlLibraryOperators;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlOperandCountRanges;
import org.apache.calcite.sql.type.SqlOperandTypeChecker;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlSingleOperandTypeChecker;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeTransforms;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.util.Util;
import org.dbsp.sqlCompiler.compiler.errors.CompilationError;
import org.dbsp.sqlCompiler.compiler.frontend.calciteObject.CalciteObject;
import org.dbsp.util.Utilities;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static java.util.Objects.requireNonNull;
import static org.apache.calcite.sql.type.OperandTypes.family;
import static org.apache.calcite.sql.type.OperandTypes.sequence;
import static org.apache.calcite.sql.type.ReturnTypes.ARG1;
import static org.apache.calcite.util.Static.RESOURCE;

/** Several functions that we define and add to the existing ones. */
public class CustomFunctions {
    private final List<NonOptimizedFunction> functions;
    private final HashMap<ProgramIdentifier, ExternalFunction> udf;

    public CustomFunctions() {
        this.functions = new ArrayList<>();
        this.functions.add(new RlikeFunction());
        this.functions.add(new GunzipFunction());
        this.functions.add(new WriteLogFunction());
        this.functions.add(new SequenceFunction());
        this.functions.add(new ToIntFunction());
        this.functions.add(new NowFunction());
        this.functions.add(new ParseJsonFunction());
        this.functions.add(new ToJsonFunction());
        this.functions.add(new BlackboxFunction());
        this.functions.add(new ParseTimeFunction());
        this.functions.add(new ParseDateFunction());
        this.functions.add(new ParseTimestampFunction());
        this.functions.add(new FormatDateFunction());
        this.functions.add(new ArrayExcept());
        this.functions.add(new ArrayUnion());
        this.functions.add(new ArrayIntersect());
        this.functions.add(new ArrayInsertFunction());
        this.functions.add(new ArraysOverlapFunction());
        this.functions.add(new ArrayRemoveFunction());
        this.functions.add(new ArrayContainsFunction());
        this.functions.add(new ArrayPositionFunction());
        this.udf = new HashMap<>();
    }

    /** Make a copy of the other object */
    public CustomFunctions(CustomFunctions other) {
        this.functions = new ArrayList<>(other.functions);
        this.udf = new HashMap<>(other.udf);
    }

    public Collection<? extends FunctionDocumentation.FunctionDescription> getDescriptions() {
        return this.functions;
    }

    /** Function that has no implementation for the optimizer */
    static abstract class NonOptimizedFunction extends SqlFunction
        implements FunctionDocumentation.FunctionDescription
    {
        final String documentationFile;

        public NonOptimizedFunction(
                String name, SqlKind kind,
                @org.checkerframework.checker.nullness.qual.Nullable SqlReturnTypeInference returnTypeInference,
                @org.checkerframework.checker.nullness.qual.Nullable SqlOperandTypeChecker operandTypeChecker,
                SqlFunctionCategory category,
                String documentationFile) {
            super(name, kind, returnTypeInference, null, operandTypeChecker, category);
            this.documentationFile = documentationFile;
        }

        public NonOptimizedFunction(
                String name,
                @org.checkerframework.checker.nullness.qual.Nullable SqlReturnTypeInference returnTypeInference,
                @org.checkerframework.checker.nullness.qual.Nullable SqlOperandTypeChecker operandTypeChecker,
                SqlFunctionCategory category,
                String documentationFile) {
            super(name, SqlKind.OTHER_FUNCTION, returnTypeInference,
                    null, operandTypeChecker, category);
            this.documentationFile = documentationFile;
        }

        @Override
        public boolean isDeterministic() {
            // Pretend that the function is not deterministic, so that the constant
            // folding code never tries to optimize it.
            return false;
        }

        @Override
        public String functionName() {
            return this.getName();
        }

        @Override
        public String documentation() {
            return this.documentationFile;
        }

        @Override
        public boolean aggregate() {
            return false;
        }
    }

    /** A clone of a Calcite SqlLibraryOperator function, but which is non-optimized */
    static abstract class CalciteFunctionClone extends NonOptimizedFunction {
        public CalciteFunctionClone(SqlFunction calciteFunction, String documentationFile) {
            super(calciteFunction.getName(), calciteFunction.kind,
                    calciteFunction.getReturnTypeInference(), calciteFunction.getOperandTypeChecker(),
                    calciteFunction.getFunctionType(), documentationFile);
        }
    }

    static class FormatDateFunction extends CalciteFunctionClone {
        private FormatDateFunction() {
            super(SqlLibraryOperators.FORMAT_DATE, "datetime");
        }
    }

    static class ArrayContainsFunction extends CalciteFunctionClone {
        private ArrayContainsFunction() { super(SqlLibraryOperators.ARRAY_CONTAINS, "array"); }
    }

    static class ArrayRemoveFunction extends CalciteFunctionClone {
        private ArrayRemoveFunction() { super(SqlLibraryOperators.ARRAY_REMOVE, "array"); }
    }

    static class ArrayPositionFunction extends CalciteFunctionClone {
        private ArrayPositionFunction() { super(SqlLibraryOperators.ARRAY_POSITION, "array"); }
    }

    static class ParseJsonFunction extends NonOptimizedFunction {
        private ParseJsonFunction() {
            super("PARSE_JSON",
                    ReturnTypes.VARIANT.andThen(SqlTypeTransforms.TO_NULLABLE),
                    OperandTypes.STRING,
                    SqlFunctionCategory.STRING, "json");
        }
    }

    static class ArrayInsertFunction extends NonOptimizedFunction {
        // Due to https://issues.apache.org/jira/browse/CALCITE-6743 we cannot use
        // the Calcite ARRAY_INSERT function
        private ArrayInsertFunction() {
            super("ARRAY_INSERT",
                    ArrayInsertFunction::arrayInsertReturnType,
                    OperandTypes.ARRAY_INSERT,
                    SqlFunctionCategory.USER_DEFINED_FUNCTION,
                    "array");
        }

        private static RelDataType arrayInsertReturnType(SqlOperatorBinding opBinding) {
            List<RelDataType> operandTypes = opBinding.collectOperandTypes();
            assert operandTypes.size() == 3;
            final RelDataType arrayType = operandTypes.get(0);
            RelDataType elementType = arrayType.getComponentType();
            assert elementType != null;
            // Result element type always nullable
            elementType = opBinding.getTypeFactory().createTypeWithNullability(elementType, true);
            return SqlTypeUtil.createArrayType(opBinding.getTypeFactory(), elementType,
                    arrayType.isNullable() || operandTypes.get(1).isNullable());
        }
    }

    /** Checks that two operands have the "same" type.
     * Two string types are considered "same". */
    public static class OperandsHaveSameType implements SqlSingleOperandTypeChecker {
        @Override
        public boolean checkSingleOperandType(
                SqlCallBinding callBinding, SqlNode operand,
                int iFormalOperand, boolean throwOnFailure) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SqlOperandCountRange getOperandCountRange() {
            return SqlOperandCountRanges.of(2);
        }

        boolean sameType(RelDataType first, RelDataType second) {
            SqlTypeName firstName = first.getSqlTypeName();
            SqlTypeName secondName = second.getSqlTypeName();
            if (SqlTypeName.CHAR_TYPES.contains(firstName)) {
                return SqlTypeName.CHAR_TYPES.contains(secondName);
            } else if (firstName == SqlTypeName.ARRAY) {
                if (secondName != SqlTypeName.ARRAY)
                    return false;
                return this.sameType(
                        Objects.requireNonNull(first.getComponentType()),
                        Objects.requireNonNull(second.getComponentType()));
            } else if (firstName == SqlTypeName.MAP) {
                if (secondName != SqlTypeName.MAP)
                    return false;
                return this.sameType(
                        Objects.requireNonNull(first.getKeyType()),
                        Objects.requireNonNull(second.getKeyType())) &&
                    this.sameType(
                            Objects.requireNonNull(first.getValueType()),
                            Objects.requireNonNull(second.getValueType()));
            } else if (first.isStruct()) {
                if (!second.isStruct()) {
                    return false;
                }
                if (first.getFieldCount() != second.getFieldCount()) {
                    return false;
                }
                List<RelDataTypeField> fields1 = first.getFieldList();
                List<RelDataTypeField> fields2 = second.getFieldList();
                for (int i = 0; i < fields1.size(); ++i) {
                    if (!this.sameType(
                            fields1.get(i).getType(),
                            fields2.get(i).getType())) {
                        return false;
                    }
                }
                return true;
            } else {
                return SqlTypeUtil.sameNamedType(first, second);
            }
        }

        @Override
        public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
            int operands = callBinding.getOperandCount();
            final List<Integer> operandList = Util.range(operands);
            RelDataType firstType = null;
            for (int i : operandList) {
                RelDataType type = callBinding.getOperandType(i);
                if (firstType != null) {
                    boolean same = this.sameType(firstType, type);
                    if (!same) {
                        if (!throwOnFailure) {
                            return false;
                        }
                        throw requireNonNull(callBinding, "callBinding").newValidationError(
                                RESOURCE.needSameTypeParameter());
                    }
                } else {
                    firstType = type;
                }
            }
            return true;
        }

        @Override
        public String getAllowedSignatures(SqlOperator op, String opName) {
            return "<T>, <T>";
        }
    }

    public static final SqlSingleOperandTypeChecker SAME_TYPE = new OperandsHaveSameType();

    private static class ArraysOverlapFunction extends NonOptimizedFunction {
        ArraysOverlapFunction() {
            super("ARRAYS_OVERLAP",
                    ReturnTypes.BOOLEAN_NULLABLE,
                    SAME_TYPE.and(OperandTypes.family(SqlTypeFamily.ARRAY, SqlTypeFamily.ARRAY)),
                    SqlFunctionCategory.USER_DEFINED_FUNCTION,
                    "array");
        }
    }

    static class ToJsonFunction extends NonOptimizedFunction {
        private ToJsonFunction() {
            super("TO_JSON",
                    ReturnTypes.VARCHAR.andThen(SqlTypeTransforms.FORCE_NULLABLE),
                    OperandTypes.VARIANT,
                    SqlFunctionCategory.STRING, "json");
        }
    }

    /** Similar to PARSE_TIME in Calcite, but always nullable */
    static class ParseTimeFunction extends NonOptimizedFunction {
        private ParseTimeFunction() {
            super("PARSE_TIME", ReturnTypes.TIME.andThen(SqlTypeTransforms.FORCE_NULLABLE),
                    OperandTypes.STRING_STRING, SqlFunctionCategory.TIMEDATE, "datetime");
        }
    }

    /** Similar to PARSE_DATE in Calcite, but always nullable */
    static class ParseDateFunction extends NonOptimizedFunction {
        private ParseDateFunction() {
            super("PARSE_DATE", ReturnTypes.DATE.andThen(SqlTypeTransforms.FORCE_NULLABLE),
                    OperandTypes.STRING_STRING, SqlFunctionCategory.TIMEDATE, "datetime");
        }
    }

    /* Similar to PARSE_TIMESTAMP in Calcite, but always nullable */
    static class ParseTimestampFunction extends NonOptimizedFunction {
        private ParseTimestampFunction() {
            super("PARSE_TIMESTAMP", ReturnTypes.TIMESTAMP.andThen(SqlTypeTransforms.FORCE_NULLABLE),
                    OperandTypes.STRING_STRING, SqlFunctionCategory.TIMEDATE, "datetime");
        }
    }

    /** RLIKE used as a function.  RLIKE in SQL uses infix notation */
    static class RlikeFunction extends NonOptimizedFunction {
        private RlikeFunction() {
            super("RLIKE",
                    SqlKind.RLIKE,
                    ReturnTypes.BOOLEAN_NULLABLE,
                    OperandTypes.STRING_STRING,
                    SqlFunctionCategory.STRING, "string");
        }
    }

    static class NowFunction extends NonOptimizedFunction {
        private NowFunction() {
            super("NOW",
                    ReturnTypes.TIMESTAMP,
                    OperandTypes.NILADIC,
                    SqlFunctionCategory.TIMEDATE, "datetime");
        }
    }

    /** GUNZIP(binary) returns the string that results from decompressing the
     * input binary using the GZIP algorithm.  The input binary must be a
     * valid GZIP binary string. */
    static class GunzipFunction extends NonOptimizedFunction {
        private GunzipFunction() {
            super("GUNZIP",
                    ReturnTypes.VARCHAR
                            .andThen(SqlTypeTransforms.TO_NULLABLE),
                    OperandTypes.BINARY,
                    SqlFunctionCategory.STRING, "binary");
        }
    }

    /** WRITELOG(format, arg) returns its argument 'arg' unchanged but also logs
     * its value to stdout.  Used for debugging.  In the format string
     * each occurrence of %% is replaced with the arg */
    static class WriteLogFunction extends NonOptimizedFunction {
        private WriteLogFunction() {
            super("WRITELOG",
                    ARG1,
                    family(SqlTypeFamily.CHARACTER, SqlTypeFamily.ANY),
                    SqlFunctionCategory.USER_DEFINED_FUNCTION, "");
        }
    }

    /** SEQUENCE(start, end) returns an array of integers from start to end (inclusive).
     * The array is empty if start > end. */
    static class SequenceFunction extends NonOptimizedFunction {
        private SequenceFunction() {
            super("SEQUENCE",
                    ReturnTypes.INTEGER
                            .andThen(SqlTypeTransforms.TO_ARRAY)
                            .andThen(SqlTypeTransforms.TO_NULLABLE),
                    family(SqlTypeFamily.INTEGER, SqlTypeFamily.INTEGER),
                    SqlFunctionCategory.USER_DEFINED_FUNCTION, "integer");
        }
    }

    /** TO_INT(BINARY) returns an integers from a BINARY object which has less than 4 bytes.
     * For VARBINARY objects it converts only the first 4 bytes. */
    static class ToIntFunction extends NonOptimizedFunction {
        private ToIntFunction() {
            super("TO_INT",
                    ReturnTypes.INTEGER
                            .andThen(SqlTypeTransforms.TO_NULLABLE),
                    OperandTypes.BINARY,
                    SqlFunctionCategory.NUMERIC, "binary");
        }
    }

    static class BlackboxFunction extends NonOptimizedFunction {
        private BlackboxFunction() {
            super("BLACKBOX",
                    ReturnTypes.ARG0,
                    OperandTypes.ANY,
                    SqlFunctionCategory.USER_DEFINED_FUNCTION, "");
        }
    }

    // This function is non-deterministic in Calcite, since it does not
    // establish the order of elements in the result.
    static class ArrayExcept extends CalciteFunctionClone {
        private ArrayExcept() {
            super(SqlLibraryOperators.ARRAY_EXCEPT, "array");
        }
    }

    // This function is non-deterministic in Calcite, since it does not
    // establish the order of elements in the result.
    static class ArrayUnion extends CalciteFunctionClone {
        private ArrayUnion() {
            super(SqlLibraryOperators.ARRAY_UNION, "array");
        }
    }

    // This function is non-deterministic in Calcite, since it does not
    // establish the order of elements in the result.
    static class ArrayIntersect extends CalciteFunctionClone {
        private ArrayIntersect() {
            super(SqlLibraryOperators.ARRAY_INTERSECT, "array");
        }
    }

    /**
     * Create a new user-defined function.
     * @param name       Function name.
     * @param signature  Description of arguments as a struct.
     * @param returnType Return type of function.
     * @param body       Optional body of the function.  If missing,
     *                   the function is defined in Rust.
     */
    public ExternalFunction createUDF(CalciteObject node, SqlIdentifier name,
                                      RelDataType signature, RelDataType returnType, @Nullable RexNode body) {
        List<RelDataTypeField> parameterList = signature.getFieldList();
        ProgramIdentifier functionName = Utilities.toIdentifier(name);
        boolean generated = functionName.name().toLowerCase(Locale.ENGLISH).startsWith("jsonstring_as_") || body != null;
        ExternalFunction result = new ExternalFunction(name, returnType, parameterList, body, generated);
        if (this.udf.containsKey(functionName)) {
            throw new CompilationError("Function with name " +
                    functionName.singleQuote() + " already exists", node);
        }
        Utilities.putNew(this.udf, functionName, result);
        return result;
    }

    @Nullable
    public ExternalFunction getSignature(ProgramIdentifier function) {
        return this.udf.get(function);
    }

    /** Return the list custom functions we added to the library. */
    public List<? extends SqlFunction> getInitialFunctions() {
        return this.functions;
    }
}
