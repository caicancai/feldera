package org.dbsp.sqlCompiler.compiler.frontend.statements;

import org.dbsp.sqlCompiler.compiler.frontend.calciteCompiler.ParsedStatement;
import org.dbsp.sqlCompiler.compiler.frontend.calciteCompiler.ProgramIdentifier;
import org.dbsp.sqlCompiler.compiler.frontend.calciteCompiler.RelColumnMetadata;

import java.util.List;

/** A statement which declares a recursive view */
public class DeclareViewStatement extends CreateRelationStatement {
    public static final String declSuffix = "-decl";

    public DeclareViewStatement(ParsedStatement node, ProgramIdentifier relationName,
                                List<RelColumnMetadata> columns) {
        super(node, relationName, columns, null);
    }

    /** Given a view name, return the name of the corresponding fake "port" view */
    public static ProgramIdentifier inputViewName(ProgramIdentifier name) {
        return new ProgramIdentifier(name.name() + declSuffix, name.isQuoted());
    }

    /** Not the actual view name, but a name for a fake temporary view */
    @Override
    public ProgramIdentifier getName() {
        return inputViewName(super.getName());
    }
}
