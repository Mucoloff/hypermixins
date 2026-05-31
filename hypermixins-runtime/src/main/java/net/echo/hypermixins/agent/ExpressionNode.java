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

    /**
     * Binary arithmetic operator. {@code op} is one of {@code '+'}, {@code '-'}, {@code '*'},
     * {@code '/'}. Matches the corresponding IADD/LADD/FADD/DADD (and variants) instruction
     * whose two stack inputs back to sub-expressions {@code lhs} and {@code rhs}.
     */
    record BinaryOp(char op, ExpressionNode lhs, ExpressionNode rhs) implements ExpressionNode {}

    /**
     * Comparison operator. {@code op} is one of {@code "=="}, {@code "!="}, {@code "<"},
     * {@code "<="}, {@code ">"}, {@code ">="}. Matches the corresponding IF_ICMP* /
     * IF_ACMP* instruction whose two stack inputs back to {@code lhs} / {@code rhs}.
     */
    record Comparison(String op, ExpressionNode lhs, ExpressionNode rhs) implements ExpressionNode {}

    /**
     * Short-circuit logical operator. {@code op} is {@code "&&"} or {@code "||"}. v7 supports a
     * single operator over two {@link Comparison} operands, matched as a two-jump region.
     */
    record LogicalOp(String op, ExpressionNode lhs, ExpressionNode rhs) implements ExpressionNode {}

    /** {@code expr instanceof TypeId}. Matches an {@code INSTANCEOF} insn whose operand type matches the resolved {@code @Definition.type()}. */
    record InstanceOf(ExpressionNode operand, String typeDefId) implements ExpressionNode {}

    /** {@code (TypeId) expr}. Matches a {@code CHECKCAST} insn whose operand type matches the resolved {@code @Definition.type()}. */
    record Cast(String typeDefId, ExpressionNode operand) implements ExpressionNode {}

    /** Argument shape inside a {@link Call} or {@link Member}. */
    sealed interface Arg {}

    /** {@code ?} — unbound capture slot (matcher) or expression operand (parser). */
    record Wildcard() implements ExpressionNode, Arg {
        public static final Wildcard INSTANCE = new Wildcard();
    }

    /** Named identifier in arg position. Resolves to a handler param by {@code -parameters} name. */
    record NamedArg(String name) implements ExpressionNode, Arg {}

    /**
     * Literal constant in arg position. Acts as a *constraint* on the matched call: the stack
     * slot at the corresponding position must be produced by a constant load whose value
     * equals {@link #value()}. Never binds to a handler param.
     */
    record LiteralArg(Kind kind, Object value) implements ExpressionNode, Arg {
        public enum Kind { INT, STRING, BOOL, NULL }
    }
}
