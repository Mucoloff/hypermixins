package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.Definition;
import net.echo.hypermixins.annotations.Definitions;
import net.echo.hypermixins.annotations.Expression;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiled form of a handler's {@code @Definition} + {@code @Expression} pair. Built once per
 * mapping inside {@link InjectPass} and reused for every candidate target instruction.
 *
 * <p>v2 capabilities:
 * <ul>
 *   <li>Unchained {@code Call} / {@code FieldRef} (v1 — unchanged).</li>
 *   <li>{@code Chained(this, member)} — owner / receiver check against ALOAD 0.</li>
 *   <li>{@code Assign(lhs, ?)} — PUTFIELD only.</li>
 *   <li>{@code ?} capture: bind each placeholder to a target local via {@link
 *       #captureSlots(AbstractInsnNode)} (backwalk to {@code *LOAD}).</li>
 * </ul>
 */
final class ExpressionMatcher {

    private final ExpressionNode root;
    private final Map<String, Definition> defsById;
    /** Number of handler params after {@code Object self}. When 0, {@code ?} placeholders are inert. */
    private final int captureArity;
    /** Handler param name → param index (1 = first capture, 2 = second, ...). Empty when -parameters absent. */
    private final Map<String, Integer> paramIndexByName;
    /** Handler declaring class (for diagnostic messages). */
    private final Method handler;

    private ExpressionMatcher(ExpressionNode root, Map<String, Definition> defsById,
                              int captureArity, Map<String, Integer> paramIndexByName, Method handler) {
        this.root = root;
        this.defsById = defsById;
        this.captureArity = captureArity;
        this.paramIndexByName = paramIndexByName;
        this.handler = handler;
    }

    static ExpressionMatcher compile(Method handler) {
        Expression expr = handler.getAnnotation(Expression.class);
        if (expr == null) {
            throw new IllegalStateException(
                "@At(point = EXPRESSION) requires @Expression on " + handler);
        }
        Map<String, Definition> defs = new HashMap<>();
        for (Definition d : collectDefinitions(handler)) {
            if (d.id().isEmpty()) {
                throw new IllegalStateException("@Definition.id() must be non-empty on " + handler);
            }
            if (d.method().isEmpty() == d.field().isEmpty()) {
                throw new IllegalStateException(
                    "@Definition id='" + d.id() + "' on " + handler
                    + " must set exactly one of method() / field()");
            }
            if (defs.put(d.id(), d) != null) {
                throw new IllegalStateException(
                    "Duplicate @Definition id='" + d.id() + "' on " + handler);
            }
        }
        ExpressionNode root = ExpressionParser.parse(expr.value());
        int handlerParams = handler.getParameterCount();
        int captureArity = Math.max(0, handlerParams - 1);
        Map<String, Integer> paramIndexByName = indexParamsByName(handler);
        validate(root, defs, handler, paramIndexByName);
        return new ExpressionMatcher(root, defs, captureArity, paramIndexByName, handler);
    }

    private static Map<String, Integer> indexParamsByName(Method handler) {
        java.lang.reflect.Parameter[] ps = handler.getParameters();
        Map<String, Integer> out = new HashMap<>();
        for (int i = 1; i < ps.length; i++) {
            if (!ps[i].isNamePresent()) return Map.of();
            out.put(ps[i].getName(), i);
        }
        return out;
    }

    boolean matches(AbstractInsnNode insn) {
        return switch (root) {
            case ExpressionNode.Call c -> matchesCall(insn, c, /*requireThis*/ false);
            case ExpressionNode.FieldRef f -> matchesFieldRef(insn, f, /*requireThis*/ false, /*store*/ null);
            case ExpressionNode.Chained ch -> matchesChained(insn, ch, /*store*/ null);
            case ExpressionNode.Assign a -> matchesAssign(insn, a);
            case ExpressionNode.ThisRef ignored -> false;
            case ExpressionNode.Member ignored -> false;
        };
    }

    /**
     * Returns {@code paramIndex → targetSlot} bindings for every {@code ?} placeholder in the
     * matched expression. Caller must have already verified {@link #matches(AbstractInsnNode)}.
     * Throws {@link InjectSignatureMismatch} when any {@code ?} argument cannot be resolved
     * to a clean {@code *LOAD} predecessor — {@code @Surrogate} fallback can retry.
     */
    Map<Integer, Integer> captureSlots(AbstractInsnNode insn) {
        if (captureArity == 0) return Map.of();
        List<ExpressionNode.Arg> args = extractCallArgs(root);
        if (args == null || args.isEmpty()) return Map.of();
        boolean hasReceiver = receiverPresent(insn, root);
        int totalStackInputs = args.size() + (hasReceiver ? 1 : 0);
        Map<Integer, Integer> out = new HashMap<>();
        for (int argIdx = 0; argIdx < args.size(); argIdx++) {
            ExpressionNode.Arg a = args.get(argIdx);
            int paramIdx;
            if (a instanceof ExpressionNode.Wildcard) {
                paramIdx = 1 + argIdx;
            } else if (a instanceof ExpressionNode.NamedArg na) {
                Integer resolved = paramIndexByName.get(na.name());
                if (resolved == null) {
                    // Should have been caught at compile time.
                    throw new InjectSignatureMismatch(
                        "@Expression named capture '" + na.name() + "' did not resolve to a handler param on " + handler);
                }
                paramIdx = resolved;
            } else {
                continue;
            }
            // Stack offset (0 = top) for argIdx: args are pushed in declaration order,
            // so argIdx's stack-from-top offset is (args.size() - 1 - argIdx).
            int stackOffset = args.size() - 1 - argIdx;
            int slot = ExpressionStackWalker.findLoadSlotForStackPosition(insn, stackOffset);
            if (slot < 0) {
                throw new InjectSignatureMismatch(
                    "@Expression arg " + argIdx + " at " + insn
                    + " is not produced by a clean *LOAD — cannot bind to a target slot");
            }
            out.put(paramIdx, slot);
        }
        // Suppress unused-variable warnings; totalStackInputs is retained for future receiver
        // captures in v3.
        if (totalStackInputs < 0) throw new AssertionError();
        return out;
    }

    private boolean matchesCall(AbstractInsnNode insn, ExpressionNode.Call call, boolean requireThis) {
        if (!(insn instanceof MethodInsnNode mi)) return false;
        Definition d = Objects.requireNonNull(defsById.get(call.defId()));
        if (!DescriptorMatcher.matches(d.method(), mi.owner + "." + mi.name + mi.desc)) return false;
        if (countParams(mi.desc) != call.args().size()) return false;
        if (requireThis && !receiverIsThis(insn, call.args().size())) return false;
        return true;
    }

    private boolean matchesFieldRef(
        AbstractInsnNode insn, ExpressionNode.FieldRef ref, boolean requireThis, Boolean wantStore
    ) {
        if (!(insn instanceof FieldInsnNode fi)) return false;
        if (wantStore != null) {
            boolean isStore = fi.getOpcode() == Opcodes.PUTFIELD || fi.getOpcode() == Opcodes.PUTSTATIC;
            if (isStore != wantStore) return false;
        }
        Definition d = Objects.requireNonNull(defsById.get(ref.defId()));
        if (!DescriptorMatcher.matches(d.field(), fi.owner + "." + fi.name + ":" + fi.desc)) return false;
        if (requireThis) {
            int isStatic = (fi.getOpcode() == Opcodes.GETSTATIC || fi.getOpcode() == Opcodes.PUTSTATIC) ? 1 : 0;
            if (isStatic == 1) return false;
            boolean store = fi.getOpcode() == Opcodes.PUTFIELD;
            int receiverStackOffset = store ? 1 : 0;
            if (!ExpressionStackWalker.producerIsAload0(insn, receiverStackOffset)) return false;
        }
        return true;
    }

    private boolean matchesChained(AbstractInsnNode insn, ExpressionNode.Chained ch, Boolean wantStore) {
        ExpressionNode.Member m = ch.member();
        ExpressionNode receiver = ch.receiver();
        boolean memberMatched;
        int argCount;
        if (m.isCall()) {
            memberMatched = matchesCall(insn, new ExpressionNode.Call(m.defId(), m.args()), /*requireThis*/ false);
            argCount = m.args().size();
        } else {
            memberMatched = matchesFieldRef(insn, new ExpressionNode.FieldRef(m.defId()), /*requireThis*/ false, wantStore);
            FieldInsnNode fi = insn instanceof FieldInsnNode ? (FieldInsnNode) insn : null;
            argCount = fi != null && fi.getOpcode() == Opcodes.PUTFIELD ? 1 : 0;
        }
        if (!memberMatched) return false;
        if (receiver instanceof ExpressionNode.ThisRef) {
            return ExpressionStackWalker.producerIsAload0(insn, argCount);
        }
        AbstractInsnNode producer = ExpressionStackWalker.findProducerAt(insn, argCount);
        if (producer == null) return false;
        return matchesSubExpression(producer, receiver);
    }

    private boolean matchesSubExpression(AbstractInsnNode insn, ExpressionNode node) {
        return switch (node) {
            case ExpressionNode.Call c -> matchesCall(insn, c, /*requireThis*/ false);
            case ExpressionNode.FieldRef f -> matchesFieldRef(insn, f, /*requireThis*/ false, /*store*/ null);
            case ExpressionNode.Chained ch -> matchesChained(insn, ch, /*store*/ null);
            case ExpressionNode.ThisRef ignored ->
                insn instanceof org.objectweb.asm.tree.VarInsnNode v
                    && v.getOpcode() == Opcodes.ALOAD && v.var == 0;
            default -> false;
        };
    }

    private boolean matchesAssign(AbstractInsnNode insn, ExpressionNode.Assign a) {
        if (!(insn instanceof FieldInsnNode)) return false;
        return switch (a.lhs()) {
            case ExpressionNode.FieldRef f -> matchesFieldRef(insn, f, false, Boolean.TRUE);
            case ExpressionNode.Chained ch -> matchesChained(insn, ch, Boolean.TRUE);
            default -> false;
        };
    }

    private boolean receiverIsThis(AbstractInsnNode site, int argCount) {
        return ExpressionStackWalker.producerIsAload0(site, argCount);
    }

    private static List<ExpressionNode.Arg> extractCallArgs(ExpressionNode root) {
        return switch (root) {
            case ExpressionNode.Call c -> c.args();
            case ExpressionNode.Chained ch when ch.member().isCall() -> ch.member().args();
            default -> null;
        };
    }

    private static boolean receiverPresent(AbstractInsnNode insn, ExpressionNode root) {
        if (!(insn instanceof MethodInsnNode mi)) return false;
        return mi.getOpcode() != Opcodes.INVOKESTATIC;
    }

    private static void validate(ExpressionNode root, Map<String, Definition> defs, Method handler,
                                 Map<String, Integer> paramIndexByName) {
        switch (root) {
            case ExpressionNode.Call c -> validateMemberRef(c.defId(), defs, handler, /*isCall*/ true);
            case ExpressionNode.FieldRef f -> validateMemberRef(f.defId(), defs, handler, /*isCall*/ false);
            case ExpressionNode.Chained ch -> {
                validateMemberRef(ch.member().defId(), defs, handler, ch.member().isCall());
                ExpressionNode r = ch.receiver();
                switch (r) {
                    case ExpressionNode.ThisRef ignored -> { /* leaf — always allowed in receiver position */ }
                    case ExpressionNode.Call innerCall -> {
                        validateMemberRef(innerCall.defId(), defs, handler, true);
                        rejectInnerCaptures(innerCall.args(), handler);
                    }
                    case ExpressionNode.FieldRef innerField ->
                        validateMemberRef(innerField.defId(), defs, handler, false);
                    case ExpressionNode.Chained innerCh ->
                        validate(innerCh, defs, handler, paramIndexByName);
                    default -> throw new IllegalStateException(
                        "@Expression chained receiver must be `this`, a call, or another chain on " + handler);
                }
            }
            case ExpressionNode.Assign a -> {
                if (!(a.rhs() instanceof ExpressionNode.Wildcard)) {
                    throw new IllegalStateException(
                        "@Expression assignment rhs must be `?` on " + handler);
                }
                validate(a.lhs(), defs, handler, paramIndexByName);
                // lhs cannot be a Call.
                if (a.lhs() instanceof ExpressionNode.Call
                    || (a.lhs() instanceof ExpressionNode.Chained ch && ch.member().isCall())) {
                    throw new IllegalStateException(
                        "@Expression assignment lhs cannot be a call on " + handler);
                }
            }
            case ExpressionNode.ThisRef ignored -> throw new IllegalStateException(
                "@Expression cannot be bare `this` on " + handler);
            case ExpressionNode.Member ignored -> throw new IllegalStateException(
                "Internal: bare Member should have been wrapped as Call or FieldRef on " + handler);
        }
        validateArgNames(root, handler, paramIndexByName);
    }

    private static void rejectInnerCaptures(List<ExpressionNode.Arg> args, Method handler) {
        for (ExpressionNode.Arg a : args) {
            if (a instanceof ExpressionNode.Wildcard || a instanceof ExpressionNode.NamedArg) {
                throw new IllegalStateException(
                    "@Expression: captures inside an inner (non-leaf) chained call are not"
                    + " supported in v3 on " + handler);
            }
        }
    }

    private static void validateMemberRef(String id, Map<String, Definition> defs, Method handler, boolean isCall) {
        Definition d = defs.get(id);
        if (d == null) {
            throw new IllegalStateException(
                "@Expression references undefined id '" + id + "' on " + handler);
        }
        if (isCall && d.method().isEmpty()) {
            throw new IllegalStateException(
                "@Expression uses '" + id + "' as a call but its @Definition sets field(), not method() on " + handler);
        }
        if (!isCall && d.field().isEmpty()) {
            throw new IllegalStateException(
                "@Expression uses '" + id + "' as a field but its @Definition sets method(), not field() on " + handler);
        }
    }

    private static void validateArgNames(ExpressionNode node, Method handler,
                                         Map<String, Integer> paramIndexByName) {
        List<ExpressionNode.Arg> args = switch (node) {
            case ExpressionNode.Call c -> c.args();
            case ExpressionNode.Chained ch when ch.member().isCall() -> ch.member().args();
            default -> null;
        };
        if (args == null) return;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ExpressionNode.Arg a : args) {
            if (!(a instanceof ExpressionNode.NamedArg na)) continue;
            if (paramIndexByName.isEmpty()) {
                throw new IllegalStateException(
                    "@Expression named capture '" + na.name() + "' on " + handler
                    + " requires the handler to be compiled with -parameters."
                    + " Add `tasks.withType<JavaCompile> { options.compilerArgs.add(\"-parameters\") }`"
                    + " (or the Kotlin equivalent) or use `?` positional captures.");
            }
            if (!paramIndexByName.containsKey(na.name())) {
                throw new IllegalStateException(
                    "@Expression named capture '" + na.name() + "' on " + handler
                    + " does not match any handler param. Available: " + paramIndexByName.keySet());
            }
            if (!seen.add(na.name())) {
                throw new IllegalStateException(
                    "@Expression on " + handler + " binds the same name '" + na.name()
                    + "' more than once");
            }
        }
    }

    private static Definition[] collectDefinitions(Method handler) {
        Definition single = handler.getAnnotation(Definition.class);
        if (single != null) return new Definition[] { single };
        Definitions group = handler.getAnnotation(Definitions.class);
        if (group != null) return group.value();
        return new Definition[0];
    }

    private static int countParams(String methodDesc) {
        int open = methodDesc.indexOf('(');
        int close = methodDesc.indexOf(')');
        if (open < 0 || close < 0 || close <= open) return 0;
        String args = methodDesc.substring(open + 1, close);
        int count = 0;
        int i = 0;
        while (i < args.length()) {
            char c = args.charAt(i);
            if (c == 'L') {
                int end = args.indexOf(';', i);
                if (end < 0) return count;
                i = end + 1;
            } else if (c == '[') {
                i++;
                continue;
            } else {
                i++;
            }
            count++;
        }
        return count;
    }
}
