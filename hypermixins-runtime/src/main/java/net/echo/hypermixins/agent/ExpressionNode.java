package net.echo.hypermixins.agent;

import java.util.List;

/**
 * Sealed AST for the {@code @Expression} DSL. v1 supports only single-instruction roots:
 * a method call or a bare field reference.
 */
sealed interface ExpressionNode {

    /** Method invocation. {@code defId} resolves against a {@code @Definition.id()}. */
    record Call(String defId, List<Arg> args) implements ExpressionNode {}

    /** Field access (load or store). {@code defId} resolves against a {@code @Definition.id()}. */
    record FieldRef(String defId) implements ExpressionNode {}

    /** Argument placeholder. v1 only supports {@link Wildcard}. */
    sealed interface Arg {}

    /** {@code ?} — unbound, matches any value at the corresponding stack slot. */
    record Wildcard() implements Arg {
        public static final Wildcard INSTANCE = new Wildcard();
    }
}
