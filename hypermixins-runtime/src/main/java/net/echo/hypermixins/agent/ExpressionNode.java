package net.echo.hypermixins.agent;

import java.util.List;

/**
 * Sealed AST for the {@code @Expression} DSL.
 *
 * <p>v1 roots: {@link Call} and {@link FieldRef} — single-instruction matchers with no
 * receiver constraint.
 * <p>v2 roots add receiver-aware and assignment-aware shapes:
 * <ul>
 *   <li>{@link Chained}: {@code this.member} where the receiver was loaded from slot 0.</li>
 *   <li>{@link Assign}: a field write of the form {@code lhs = ?}, matching PUTFIELD only.</li>
 * </ul>
 * Arguments inside a {@link Call} or {@link Member} are {@link Arg} values — {@code ?} for an
 * unbound capture, {@code this} for a target-instance reference (compile-time validated).
 */
sealed interface ExpressionNode {

    /** Method invocation (no explicit receiver). */
    record Call(String defId, List<Arg> args) implements ExpressionNode {}

    /** Bare field reference (no explicit receiver). Matches both GETFIELD and PUTFIELD. */
    record FieldRef(String defId) implements ExpressionNode {}

    /**
     * Member access against an explicit receiver. {@code isCall = true} indicates a method
     * invocation form ({@code member(args)}); {@code false} is a field reference.
     */
    record Member(String defId, List<Arg> args, boolean isCall) implements ExpressionNode {}

    /** Receiver-qualified access: {@code this.member}. v2 only supports {@code this} as receiver. */
    record Chained(ExpressionNode receiver, Member member) implements ExpressionNode {}

    /** Field assignment: {@code lhs = ?}. Matches PUTFIELD only. */
    record Assign(ExpressionNode lhs, Arg rhs) implements ExpressionNode {}

    /** {@code this} keyword. Used as a receiver or as a call argument. */
    record ThisRef() implements ExpressionNode, Arg {
        public static final ThisRef INSTANCE = new ThisRef();
    }

    /** Argument shape inside a {@link Call} or {@link Member}. */
    sealed interface Arg {}

    /** {@code ?} — unbound capture slot. */
    record Wildcard() implements Arg {
        public static final Wildcard INSTANCE = new Wildcard();
    }

    /** Named identifier in arg position. Reserved for v3 named captures; v2 rejects at use. */
    record NamedArg(String name) implements Arg {}
}
