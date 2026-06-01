package net.echo.hypermixins.agent;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
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
    private final Map<String, MixinDescriptor.DefinitionEntry> defsById;
    /** Number of handler params after {@code Object self}. When 0, {@code ?} placeholders are inert. */
    private final int captureArity;
    /** Handler param name → param index (1 = first capture, 2 = second, ...). Empty when -parameters absent. */
    private final Map<String, Integer> paramIndexByName;
    /** Handler declaring class (for diagnostic messages). */
    private final Method handler;

    private ExpressionMatcher(ExpressionNode root, Map<String, MixinDescriptor.DefinitionEntry> defsById,
                              int captureArity, Map<String, Integer> paramIndexByName, Method handler) {
        this.root = root;
        this.defsById = defsById;
        this.captureArity = captureArity;
        this.paramIndexByName = paramIndexByName;
        this.handler = handler;
    }

    static ExpressionMatcher compile(Method handler, MixinDescriptor descriptor) {
        String handlerKey = handler.getName() + Type.getMethodDescriptor(handler);
        MixinDescriptor.ExpressionMetadata meta = descriptor.expressions().get(handlerKey);
        if (meta == null) {
            throw new IllegalStateException(
                "@At(point = EXPRESSION) requires @Expression on " + sig(handler));
        }
        Map<String, MixinDescriptor.DefinitionEntry> defs = getStringDefinitionEntryMap(handler, meta);
        ExpressionNode root = ExpressionParser.parse(meta.expression());
        int handlerParams = handler.getParameterCount();
        int captureArity = Math.max(0, handlerParams - 1);
        Map<String, Integer> paramIndexByName = indexParamsByName(handler);
        validate(root, defs, handler, paramIndexByName);
        return new ExpressionMatcher(root, defs, captureArity, paramIndexByName, handler);
    }

    private static @NotNull Map<String, MixinDescriptor.DefinitionEntry> getStringDefinitionEntryMap(Method handler, MixinDescriptor.ExpressionMetadata meta) {
        Map<String, MixinDescriptor.DefinitionEntry> defs = new HashMap<>();
        for (MixinDescriptor.DefinitionEntry d : meta.definitions()) {
            if (d.id().isEmpty()) {
                throw new IllegalStateException("@Definition.id() must be non-empty on " + sig(handler));
            }
            int nonEmpty = (d.method().isEmpty() ? 0 : 1) + (d.field().isEmpty() ? 0 : 1) + (d.type().isEmpty() ? 0 : 1);
            if (nonEmpty != 1) {
                throw new IllegalStateException(
                    "@Definition id='" + d.id() + "' on " + sig(handler)
                    + " must set exactly one of method() / field() / type()");
            }
            if (defs.put(d.id(), d) != null) {
                throw new IllegalStateException(
                    "Duplicate @Definition id='" + d.id() + "' on " + sig(handler));
            }
        }
        return defs;
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
        return matchesSubExpression(insn, root);
    }

    /**
     * Returns {@code paramIndex → targetSlot} bindings for every {@code ?} placeholder in the
     * matched expression. Caller must have already verified {@link #matches(AbstractInsnNode)}.
     * Throws {@link InjectSignatureMismatch} when any {@code ?} argument cannot be resolved
     * to a clean {@code *LOAD} predecessor — {@code @Surrogate} fallback can retry.
     */
    Map<Integer, Integer> captureSlots(AbstractInsnNode insn) {
        if (captureArity == 0) return Map.of();
        CaptureContext ctx = new CaptureContext();
        collectCaptures(root, insn, ctx);
        return ctx.out;
    }

    private static final class CaptureContext {
        int positional = 0;
        final Map<Integer, Integer> out = new HashMap<>();
    }

    /**
     * Depth-first source-order traversal that visits every {@code ?} / NamedArg leaf and
     * binds it to a target slot via {@link ExpressionStackWalker#findLoadSlotForStackPosition}.
     * Receivers of chained calls are visited before the outer call's args so positional
     * indices follow source order.
     */
    private void collectCaptures(ExpressionNode node, AbstractInsnNode anchor, CaptureContext ctx) {
        switch (node) {
            case ExpressionNode.Call c -> collectCallArgs(c.args(), anchor, ctx);
            case ExpressionNode.Chained ch -> {
                AbstractInsnNode receiverProducer;
                if (ch.member().isCall()) {
                    receiverProducer = ExpressionStackWalker.findProducerAt(anchor, ch.member().args().size());
                    if (receiverProducer != null) collectCaptures(ch.receiver(), receiverProducer, ctx);
                    collectCallArgs(ch.member().args(), anchor, ctx);
                } else {
                    int recvOff = (anchor instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD) ? 1 : 0;
                    receiverProducer = ExpressionStackWalker.findProducerAt(anchor, recvOff);
                    if (receiverProducer != null) collectCaptures(ch.receiver(), receiverProducer, ctx);
                }
            }
            case ExpressionNode.Assign a -> collectCaptures(a.lhs(), anchor, ctx);
            case ExpressionNode.BinaryOp bo -> {
                AbstractInsnNode lhsProducer = ExpressionStackWalker.findProducerAt(anchor, 1);
                AbstractInsnNode rhsProducer = ExpressionStackWalker.findProducerAt(anchor, 0);
                collectArithOperand(bo.lhs(), lhsProducer, ctx);
                collectArithOperand(bo.rhs(), rhsProducer, ctx);
            }
            case ExpressionNode.Comparison c -> {
                AbstractInsnNode lhsProducer = ExpressionStackWalker.findProducerAt(anchor, 1);
                AbstractInsnNode rhsProducer = ExpressionStackWalker.findProducerAt(anchor, 0);
                collectArithOperand(c.lhs(), lhsProducer, ctx);
                collectArithOperand(c.rhs(), rhsProducer, ctx);
            }
            case ExpressionNode.InstanceOf io -> {
                AbstractInsnNode producer = ExpressionStackWalker.findProducerAt(anchor, 0);
                collectArithOperand(io.operand(), producer, ctx);
            }
            case ExpressionNode.Cast ct -> {
                AbstractInsnNode producer = ExpressionStackWalker.findProducerAt(anchor, 0);
                collectArithOperand(ct.operand(), producer, ctx);
            }
            case ExpressionNode.LogicalOp lo -> {
                // Walk the in-order comparison leaves, advancing the anchor jump for each.
                List<ExpressionNode.Comparison> leaves = new java.util.ArrayList<>();
                collectLeaves(lo, leaves);
                {
                    AbstractInsnNode jump = anchor;
                    for (int i = 0; i < leaves.size() && jump != null; i++) {
                        collectCaptures(leaves.get(i), jump, ctx);
                        jump = (i + 1 < leaves.size()) ? nextConditionalJump(jump.getNext()) : jump;
                    }
                }
            }
            // FieldRef / ThisRef / literals / unsupported roots — no captures to extract.
            default -> {}
        }
    }

    /**
     * Operand-position capture binding for arithmetic / comparison / instanceof / cast.
     * A leaf {@code ?} or NamedArg binds to the producer instruction itself (which must be a
     * clean {@code *LOAD}); nested calls / fields recurse via {@link #collectCaptures}.
     */
    private void collectArithOperand(ExpressionNode operand, AbstractInsnNode producer, CaptureContext ctx) {
        if (producer == null) return;
        switch (operand) {
            case ExpressionNode.Wildcard ignored -> {
                ctx.positional++;
                int paramIdx = ctx.positional;
                if (!(producer instanceof org.objectweb.asm.tree.VarInsnNode v) || !isLoadOpcode(v.getOpcode())) {
                    throw new InjectSignatureMismatch(
                        "@Expression arithmetic operand ? at " + producer
                        + " is not produced by a clean *LOAD — cannot bind to a target slot");
                }
                ctx.out.put(paramIdx, v.var);
            }
            case ExpressionNode.NamedArg na -> {
                Integer resolved = paramIndexByName.get(na.name());
                if (resolved == null) {
                    throw new InjectSignatureMismatch(
                        "@Expression named capture '" + na.name() + "' did not resolve to a handler param on " + sig(handler));
                }
                if (!(producer instanceof org.objectweb.asm.tree.VarInsnNode v) || !isLoadOpcode(v.getOpcode())) {
                    throw new InjectSignatureMismatch(
                        "@Expression named operand '" + na.name() + "' at " + producer
                        + " is not produced by a clean *LOAD");
                }
                ctx.out.put(resolved, v.var);
            }
            default -> collectCaptures(operand, producer, ctx);
        }
    }

    private void collectCallArgs(List<ExpressionNode.Arg> args, AbstractInsnNode anchor, CaptureContext ctx) {
        for (int argIdx = 0; argIdx < args.size(); argIdx++) {
            ExpressionNode.Arg a = args.get(argIdx);
            int paramIdx;
            if (a instanceof ExpressionNode.Wildcard) {
                ctx.positional++;
                paramIdx = ctx.positional;
            } else if (a instanceof ExpressionNode.NamedArg(String name)) {
                Integer resolved = paramIndexByName.get(name);
                if (resolved == null) {
                    throw new InjectSignatureMismatch(
                        "@Expression named capture '" + name + "' did not resolve to a handler param on " + sig(handler));
                }
                paramIdx = resolved;
            } else {
                continue;
            }
            int stackOffset = args.size() - 1 - argIdx;
            int slot = ExpressionStackWalker.findLoadSlotForStackPosition(anchor, stackOffset);
            if (slot < 0) {
                throw new InjectSignatureMismatch(
                    "@Expression arg " + argIdx + " at " + anchor
                    + " is not produced by a clean *LOAD — cannot bind to a target slot");
            }
            ctx.out.put(paramIdx, slot);
        }
    }

    private boolean matchesCall(AbstractInsnNode insn, ExpressionNode.Call call, boolean requireThis) {
        if (!(insn instanceof MethodInsnNode mi)) return false;
        MixinDescriptor.DefinitionEntry d = Objects.requireNonNull(defsById.get(call.defId()));
        if (!DescriptorMatcher.matches(d.method(), mi.owner + "." + mi.name + mi.desc)) return false;
        if (!literalArgsMatch(insn, call.args())) return false;
        if (countParams(mi.desc) != call.args().size()) return false;
        return !requireThis || receiverIsThis(insn, call.args().size());
    }

    private boolean matchesFieldRef(
        AbstractInsnNode insn, ExpressionNode.FieldRef ref, boolean requireThis, Boolean wantStore
    ) {
        if (!(insn instanceof FieldInsnNode fi)) return false;
        if (wantStore != null) {
            boolean isStore = fi.getOpcode() == Opcodes.PUTFIELD || fi.getOpcode() == Opcodes.PUTSTATIC;
            if (isStore != wantStore) return false;
        }
        MixinDescriptor.DefinitionEntry d = Objects.requireNonNull(defsById.get(ref.defId()));
        if (!DescriptorMatcher.matches(d.field(), fi.owner + "." + fi.name + ":" + fi.desc)) return false;
        if (requireThis) {
            int isStatic = (fi.getOpcode() == Opcodes.GETSTATIC || fi.getOpcode() == Opcodes.PUTSTATIC) ? 1 : 0;
            if (isStatic == 1) return false;
            boolean store = fi.getOpcode() == Opcodes.PUTFIELD;
            int receiverStackOffset = store ? 1 : 0;
            return ExpressionStackWalker.producerIsAload0(insn, receiverStackOffset);
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
            case ExpressionNode.Assign a -> matchesAssign(insn, a);
            case ExpressionNode.BinaryOp bo -> matchesBinaryOp(insn, bo);
            case ExpressionNode.Comparison c -> matchesComparison(insn, c);
            case ExpressionNode.LogicalOp lo -> matchesLogicalOp(insn, lo);
            case ExpressionNode.InstanceOf io -> matchesInstanceOf(insn, io);
            case ExpressionNode.Cast ct -> matchesCast(insn, ct);
            case ExpressionNode.Wildcard ignored ->
                insn instanceof org.objectweb.asm.tree.VarInsnNode v && isLoadOpcode(v.getOpcode());
            case ExpressionNode.NamedArg ignored ->
                insn instanceof org.objectweb.asm.tree.VarInsnNode v && isLoadOpcode(v.getOpcode());
            case ExpressionNode.LiteralArg lit -> literalMatches(insn, lit);
            case ExpressionNode.ThisRef ignored ->
                insn instanceof org.objectweb.asm.tree.VarInsnNode v
                    && v.getOpcode() == Opcodes.ALOAD && v.var == 0;
            case ExpressionNode.Member ignored -> false;
        };
    }

    private boolean matchesBinaryOp(AbstractInsnNode insn, ExpressionNode.BinaryOp bo) {
        int op = insn.getOpcode();
        if (!opcodeMatchesOperator(op, bo.op())) return false;
        // Pre-IADD stack: [..., lhs, rhs]. Top-of-stack = rhs (offset 0); lhs at offset 1.
        // For long/double the operands are wide; producer accounting still walks one
        // logical producer per slot in v3-C.
        AbstractInsnNode rhsProducer = ExpressionStackWalker.findProducerAt(insn, 0);
        AbstractInsnNode lhsProducer = ExpressionStackWalker.findProducerAt(insn, 1);
        if (rhsProducer == null || lhsProducer == null) return false;
        return matchesSubExpression(lhsProducer, bo.lhs())
            && matchesSubExpression(rhsProducer, bo.rhs());
    }

    private boolean matchesInstanceOf(AbstractInsnNode insn, ExpressionNode.InstanceOf io) {
        if (insn.getOpcode() != Opcodes.INSTANCEOF) return false;
        if (!(insn instanceof org.objectweb.asm.tree.TypeInsnNode ti)) return false;
        MixinDescriptor.DefinitionEntry d = Objects.requireNonNull(defsById.get(io.typeDefId()));
        if (!DescriptorMatcher.matches(d.type(), ti.desc)) return false;
        AbstractInsnNode operandProducer = ExpressionStackWalker.findProducerAt(insn, 0);
        if (operandProducer == null) return false;
        return matchesSubExpression(operandProducer, io.operand());
    }

    private boolean matchesCast(AbstractInsnNode insn, ExpressionNode.Cast ct) {
        if (insn.getOpcode() != Opcodes.CHECKCAST) return false;
        if (!(insn instanceof org.objectweb.asm.tree.TypeInsnNode ti)) return false;
        MixinDescriptor.DefinitionEntry d = Objects.requireNonNull(defsById.get(ct.typeDefId()));
        if (!DescriptorMatcher.matches(d.type(), ti.desc)) return false;
        AbstractInsnNode operandProducer = ExpressionStackWalker.findProducerAt(insn, 0);
        if (operandProducer == null) return false;
        return matchesSubExpression(operandProducer, ct.operand());
    }

    private boolean matchesComparison(AbstractInsnNode insn, ExpressionNode.Comparison c) {
        int op = insn.getOpcode();
        boolean forward = insn instanceof org.objectweb.asm.tree.JumpInsnNode jump
            && ExpressionStackWalker.isForwardJump(jump);
        if (!comparisonOpcodeMatches(op, c.op(), forward)) return false;
        AbstractInsnNode rhsProducer = ExpressionStackWalker.findProducerAt(insn, 0);
        AbstractInsnNode lhsProducer = ExpressionStackWalker.findProducerAt(insn, 1);
        if (rhsProducer == null || lhsProducer == null) return false;
        return matchesSubExpression(lhsProducer, c.lhs())
            && matchesSubExpression(rhsProducer, c.rhs());
    }

    /** Overall branch-target slot during short-circuit recognition (BODY = then-block, ELSE = else-block). */
    private enum Target { BODY, ELSE }

    /**
     * Recognises the short-circuit compilation of a (possibly mixed) {@code &&} / {@code ||}
     * tree of comparisons, following javac's {@code genCond(node, trueTarget, falseTarget)}
     * rule with an explicit fall-through side. A leaf emits one jump to its non-fall-through
     * target; that target must be BODY (direct jump) or ELSE (negated jump). Returns {@code null}
     * when a leaf would have to jump to an INTERMEDIATE label (e.g. {@code (a||b) && c}, where the
     * left group's short-circuit-true exits to the start of {@code c}, not the body). 2-label
     * trees: same-operator chains and right-nested mixes like {@code a && (b || c)}.
     */
    /** In-order leaf-count of a comparison/logical tree (each comparison is one short-circuit jump). */
    private static int leafCount(ExpressionNode node) {
        if (node instanceof ExpressionNode.LogicalOp lo) {
            return leafCount(lo.lhs()) + leafCount(lo.rhs());
        }
        return 1; // comparison (or unsupported leaf — rejected elsewhere)
    }

    /** True when every leaf of a logical tree is a comparison (no calls / fields / etc.). */
    private static boolean allLeavesAreComparisons(ExpressionNode node) {
        if (node instanceof ExpressionNode.LogicalOp lo) {
            return allLeavesAreComparisons(lo.lhs()) && allLeavesAreComparisons(lo.rhs());
        }
        return node instanceof ExpressionNode.Comparison;
    }

    /** Appends the in-order comparison leaves of a logical tree. */
    private static void collectLeaves(ExpressionNode node, List<ExpressionNode.Comparison> out) {
        if (node instanceof ExpressionNode.LogicalOp lo) {
            collectLeaves(lo.lhs(), out);
            collectLeaves(lo.rhs(), out);
        } else if (node instanceof ExpressionNode.Comparison c) {
            out.add(c);
        }
    }

    /** Unifies the two overall targets (then-block / else-block), whose labels are unknown a priori. */
    private static final class TargetBox {
        org.objectweb.asm.tree.LabelNode body;
        org.objectweb.asm.tree.LabelNode elseL;
        boolean check(Target t, org.objectweb.asm.tree.LabelNode actual) {
            if (t == Target.BODY) {
                if (body == null) { body = actual; return true; }
                return body == actual;
            }
            if (elseL == null) { elseL = actual; return true; }
            return elseL == actual;
        }
    }

    /** Nearest {@code LabelNode} preceding {@code insn} — the entry label of the region it starts. */
    private static org.objectweb.asm.tree.LabelNode entryLabel(AbstractInsnNode insn) {
        for (AbstractInsnNode n = insn; n != null; n = n.getPrevious()) {
            if (n instanceof org.objectweb.asm.tree.LabelNode l) return l;
        }
        return null;
    }

    /**
     * Recursively matches a {@code &&} / {@code ||} tree of comparisons against the short-circuit
     * jump sequence {@code jumps[lo..hi]}, threading the true / false branch targets (each either
     * one of the overall BODY/ELSE slots — unified through {@code box} — or a concrete
     * intermediate {@link org.objectweb.asm.tree.LabelNode} discovered between sibling regions).
     * Handles arbitrary nesting including mixed operators like {@code (a||b) && c}.
     *
     * <p>{@code trueT} / {@code falseT} are {@link Target} (BODY/ELSE slot) or {@code LabelNode}
     * (intermediate). {@code trueIsFallthrough} marks which branch continues physically (no jump).
     */
    private boolean matchCond(ExpressionNode node, List<org.objectweb.asm.tree.JumpInsnNode> jumps,
                              int lo, int hi, Object trueT, Object falseT, boolean trueIsFallthrough,
                              TargetBox box) {
        if (node instanceof ExpressionNode.Comparison c) {
            if (lo != hi) return false;
            org.objectweb.asm.tree.JumpInsnNode j = jumps.get(lo);
            if (trueIsFallthrough) {
                // true falls through; the jump fires on false → falseT, negated opcode.
                if (!forwardOpcodeMatches(j.getOpcode(), c.op())) return false;
                if (!matchesTarget(falseT, j.label, box)) return false;
            } else {
                // false falls through; the jump fires on true → trueT, direct opcode.
                if (!backwardOpcodeMatches(j.getOpcode(), c.op())) return false;
                if (!matchesTarget(trueT, j.label, box)) return false;
            }
            return comparisonOperandsMatch(j, c);
        }
        if (node instanceof ExpressionNode.LogicalOp op) {
            int leftCount = leafCount(op.lhs());
            int mid = lo + leftCount - 1;
            if (mid >= hi) return false; // both sides need at least one jump
            org.objectweb.asm.tree.LabelNode boundary = entryLabel(jumps.get(mid + 1));
            if (boundary == null) return false;
            if ("&&".equals(op.op())) {
                // a && b: a-true falls into b (boundary); a-false short-circuits to falseT.
                if (!matchCond(op.lhs(), jumps, lo, mid, boundary, falseT, true, box)) return false;
                return matchCond(op.rhs(), jumps, mid + 1, hi, trueT, falseT, trueIsFallthrough, box);
            } else { // "||"
                // a || b: a-true short-circuits to trueT; a-false falls into b (boundary).
                if (!matchCond(op.lhs(), jumps, lo, mid, trueT, boundary, false, box)) return false;
                return matchCond(op.rhs(), jumps, mid + 1, hi, trueT, falseT, trueIsFallthrough, box);
            }
        }
        return false; // non-comparison leaf
    }

    private static boolean matchesTarget(Object target, org.objectweb.asm.tree.LabelNode actual, TargetBox box) {
        if (target instanceof org.objectweb.asm.tree.LabelNode l) return l == actual;
        return box.check((Target) target, actual);
    }

    /**
     * Matches a {@code &&} / {@code ||} tree (any nesting / mixed operators) anchored on the first
     * comparison's conditional jump, via the recursive {@link #matchCond} structural recogniser.
     */
    private boolean matchesLogicalOp(AbstractInsnNode insn, ExpressionNode.LogicalOp lo) {
        if (!(insn instanceof org.objectweb.asm.tree.JumpInsnNode)) return false;
        int k = leafCount(lo);
        List<org.objectweb.asm.tree.JumpInsnNode> jumps = new java.util.ArrayList<>(k);
        AbstractInsnNode cursor = insn;
        for (int i = 0; i < k; i++) {
            if (!(cursor instanceof org.objectweb.asm.tree.JumpInsnNode j)) return false;
            jumps.add(j);
            cursor = (i + 1 < k) ? nextConditionalJump(j.getNext()) : cursor;
        }
        TargetBox box = new TargetBox();
        if (!matchCond(lo, jumps, 0, k - 1, Target.BODY, Target.ELSE, true, box)) return false;
        // BODY and ELSE must be distinct program points when both were observed.
        return box.body == null || box.elseL == null || box.body != box.elseL;
    }

    private boolean comparisonOperandsMatch(AbstractInsnNode jump, ExpressionNode.Comparison c) {
        AbstractInsnNode rhsProducer = ExpressionStackWalker.findProducerAt(jump, 0);
        AbstractInsnNode lhsProducer = ExpressionStackWalker.findProducerAt(jump, 1);
        if (rhsProducer == null || lhsProducer == null) return false;
        return matchesSubExpression(lhsProducer, c.lhs())
            && matchesSubExpression(rhsProducer, c.rhs());
    }

    /** First conditional {@code IF_ICMP*} / {@code IF_ACMP*} jump at or after {@code start}, else null. */
    private static AbstractInsnNode nextConditionalJump(AbstractInsnNode start) {
        for (AbstractInsnNode n = start; n != null; n = n.getNext()) {
            if (n instanceof org.objectweb.asm.tree.JumpInsnNode j) {
                int op = j.getOpcode();
                boolean conditional = (op >= Opcodes.IF_ICMPEQ && op <= Opcodes.IF_ACMPNE);
                if (conditional) return n;
            }
        }
        return null;
    }

    /**
     * Maps a textual comparison operator to the {@code IF_ICMP*} / {@code IF_ACMP*} opcode that
     * carries it, disambiguated by jump direction.
     *
     * <p>javac compiles {@code if (cond)} as a jump-when-false, so a FORWARD (skip) jump uses
     * the NEGATION of the source operator ({@code if (a == b)} → {@code IF_ICMPNE}). A loop
     * bottom-test jumps BACKWARD to the body when the condition holds, so it uses the DIRECT
     * operator ({@code while (a < b)} → {@code IF_ICMPLT}). Selecting the opcode set by
     * direction makes each operator match in both shapes while keeping {@code ==}/{@code !=},
     * {@code <}/{@code >=}, {@code <=}/{@code >} distinct within a direction.
     */
    private static boolean comparisonOpcodeMatches(int op, String operator, boolean forward) {
        return forward ? forwardOpcodeMatches(op, operator) : backwardOpcodeMatches(op, operator);
    }

    private static boolean forwardOpcodeMatches(int op, String operator) {
        return switch (operator) {
            case "==" -> op == Opcodes.IF_ICMPNE || op == Opcodes.IF_ACMPNE;
            case "!=" -> op == Opcodes.IF_ICMPEQ || op == Opcodes.IF_ACMPEQ;
            case "<"  -> op == Opcodes.IF_ICMPGE;
            case "<=" -> op == Opcodes.IF_ICMPGT;
            case ">"  -> op == Opcodes.IF_ICMPLE;
            case ">=" -> op == Opcodes.IF_ICMPLT;
            default   -> false;
        };
    }

    private static boolean backwardOpcodeMatches(int op, String operator) {
        return switch (operator) {
            case "==" -> op == Opcodes.IF_ICMPEQ || op == Opcodes.IF_ACMPEQ;
            case "!=" -> op == Opcodes.IF_ICMPNE || op == Opcodes.IF_ACMPNE;
            case "<"  -> op == Opcodes.IF_ICMPLT;
            case "<=" -> op == Opcodes.IF_ICMPLE;
            case ">"  -> op == Opcodes.IF_ICMPGT;
            case ">=" -> op == Opcodes.IF_ICMPGE;
            default   -> false;
        };
    }

    private static boolean opcodeMatchesOperator(int op, char operator) {
        return switch (operator) {
            case '+' -> op == Opcodes.IADD || op == Opcodes.LADD || op == Opcodes.FADD || op == Opcodes.DADD;
            case '-' -> op == Opcodes.ISUB || op == Opcodes.LSUB || op == Opcodes.FSUB || op == Opcodes.DSUB;
            case '*' -> op == Opcodes.IMUL || op == Opcodes.LMUL || op == Opcodes.FMUL || op == Opcodes.DMUL;
            case '/' -> op == Opcodes.IDIV || op == Opcodes.LDIV || op == Opcodes.FDIV || op == Opcodes.DDIV;
            default -> false;
        };
    }

    private static boolean isLoadOpcode(int op) {
        return op == Opcodes.ILOAD || op == Opcodes.LLOAD || op == Opcodes.FLOAD
            || op == Opcodes.DLOAD || op == Opcodes.ALOAD;
    }

    /** Compact JVM-style signature for diagnostics: {@code owner.name(desc)ret}. */
    private static String sig(Method handler) {
        return handler.getDeclaringClass().getName() + "." + handler.getName()
            + Type.getMethodDescriptor(handler);
    }

    private boolean literalArgsMatch(AbstractInsnNode insn, List<ExpressionNode.Arg> args) {
        for (int i = 0; i < args.size(); i++) {
            if (!(args.get(i) instanceof ExpressionNode.LiteralArg lit)) continue;
            int stackOffset = args.size() - 1 - i;
            AbstractInsnNode producer = ExpressionStackWalker.findProducerAt(insn, stackOffset);
            if (producer == null) return false;
            if (!literalMatches(producer, lit)) return false;
        }
        return true;
    }

    private static boolean literalMatches(AbstractInsnNode producer, ExpressionNode.LiteralArg lit) {
        return switch (lit.kind()) {
            case INT -> matchesIntConst(producer, (Integer) lit.value());
            case STRING -> producer instanceof org.objectweb.asm.tree.LdcInsnNode l
                && l.cst instanceof String s && s.equals(lit.value());
            case BOOL -> producer.getOpcode() == (Boolean.TRUE.equals(lit.value())
                ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            case NULL -> producer.getOpcode() == Opcodes.ACONST_NULL;
        };
    }

    private static boolean matchesIntConst(AbstractInsnNode producer, int wanted) {
        int op = producer.getOpcode();
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
            return wanted == op - Opcodes.ICONST_0;
        }
        if (producer instanceof org.objectweb.asm.tree.IntInsnNode iin
            && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)) {
            return wanted == iin.operand;
        }
        return producer instanceof org.objectweb.asm.tree.LdcInsnNode l
            && l.cst instanceof Integer i && i == wanted;
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

    private static void validate(ExpressionNode root, Map<String, MixinDescriptor.DefinitionEntry> defs, Method handler,
                                 Map<String, Integer> paramIndexByName) {
        switch (root) {
            case ExpressionNode.Call c -> validateMemberRef(c.defId(), defs, handler, /*isCall*/ true);
            case ExpressionNode.FieldRef f -> validateMemberRef(f.defId(), defs, handler, /*isCall*/ false);
            case ExpressionNode.Chained ch -> {
                validateMemberRef(ch.member().defId(), defs, handler, ch.member().isCall());
                ExpressionNode r = ch.receiver();
                switch (r) {
                    case ExpressionNode.ThisRef ignored -> { /* leaf — always allowed in receiver position */ }
                    case ExpressionNode.Call innerCall ->
                        validateMemberRef(innerCall.defId(), defs, handler, true);
                    case ExpressionNode.FieldRef innerField ->
                        validateMemberRef(innerField.defId(), defs, handler, false);
                    case ExpressionNode.Chained innerCh ->
                        validate(innerCh, defs, handler, paramIndexByName);
                    default -> throw new IllegalStateException(
                        "@Expression chained receiver must be `this`, a call, or another chain on " + sig(handler));
                }
            }
            case ExpressionNode.Assign a -> {
                if (!(a.rhs() instanceof ExpressionNode.Wildcard)) {
                    throw new IllegalStateException(
                        "@Expression assignment rhs must be `?` on " + sig(handler));
                }
                validate(a.lhs(), defs, handler, paramIndexByName);
                // lhs cannot be a Call.
                if (a.lhs() instanceof ExpressionNode.Call
                    || (a.lhs() instanceof ExpressionNode.Chained ch && ch.member().isCall())) {
                    throw new IllegalStateException(
                        "@Expression assignment lhs cannot be a call on " + sig(handler));
                }
            }
            case ExpressionNode.BinaryOp bo -> {
                validateOperand(bo.lhs(), defs, handler, paramIndexByName);
                validateOperand(bo.rhs(), defs, handler, paramIndexByName);
            }
            case ExpressionNode.Comparison c -> {
                validateOperand(c.lhs(), defs, handler, paramIndexByName);
                validateOperand(c.rhs(), defs, handler, paramIndexByName);
            }
            case ExpressionNode.LogicalOp lo -> {
                if (!allLeavesAreComparisons(lo)) {
                    throw new IllegalStateException(
                        "@Expression `&&` / `||` operands must all be comparisons on " + sig(handler));
                }
                List<ExpressionNode.Comparison> leaves = new java.util.ArrayList<>();
                collectLeaves(lo, leaves);
                for (ExpressionNode.Comparison c : leaves) {
                    validate(c, defs, handler, paramIndexByName);
                }
            }
            case ExpressionNode.InstanceOf io -> {
                validateTypeRef(io.typeDefId(), defs, handler);
                validateOperand(io.operand(), defs, handler, paramIndexByName);
            }
            case ExpressionNode.Cast ct -> {
                validateTypeRef(ct.typeDefId(), defs, handler);
                validateOperand(ct.operand(), defs, handler, paramIndexByName);
            }
            case ExpressionNode.LiteralArg lit -> throw new IllegalStateException(
                "@Expression cannot be a bare literal '" + lit.value() + "' on " + sig(handler));
            case ExpressionNode.Wildcard ignored -> throw new IllegalStateException(
                "@Expression cannot be bare `?` on " + sig(handler));
            case ExpressionNode.NamedArg na -> throw new IllegalStateException(
                "@Expression cannot be bare named arg '" + na.name() + "' on " + sig(handler));
            case ExpressionNode.ThisRef ignored -> throw new IllegalStateException(
                "@Expression cannot be bare `this` on " + sig(handler));
            case ExpressionNode.Member ignored -> throw new IllegalStateException(
                "Internal: bare Member should have been wrapped as Call or FieldRef on " + sig(handler));
        }
        validateArgNames(root, handler, paramIndexByName);
    }

    /**
     * Validates an arithmetic operand: Wildcard / NamedArg / ThisRef are leaves (no further
     * shape constraints); calls / fields / chains delegate to the root validator; nested
     * BinaryOps recurse.
     */
    private static void validateOperand(ExpressionNode node, Map<String, MixinDescriptor.DefinitionEntry> defs,
                                        Method handler, Map<String, Integer> paramIndexByName) {
        switch (node) {
            case ExpressionNode.Wildcard ignored -> {}
            case ExpressionNode.NamedArg ignored -> {}
            case ExpressionNode.LiteralArg ignored -> {}
            case ExpressionNode.ThisRef ignored -> {}
            case ExpressionNode.BinaryOp bo -> {
                validateOperand(bo.lhs(), defs, handler, paramIndexByName);
                validateOperand(bo.rhs(), defs, handler, paramIndexByName);
            }
            case ExpressionNode.Comparison c -> {
                validateOperand(c.lhs(), defs, handler, paramIndexByName);
                validateOperand(c.rhs(), defs, handler, paramIndexByName);
            }
            default -> validate(node, defs, handler, paramIndexByName);
        }
    }

    private static void rejectInnerCaptures(List<ExpressionNode.Arg> args, Method handler) {
        for (ExpressionNode.Arg a : args) {
            if (a instanceof ExpressionNode.Wildcard || a instanceof ExpressionNode.NamedArg) {
                throw new IllegalStateException(
                    "@Expression: captures inside an inner (non-leaf) chained call are not"
                    + " supported in v3 on " + sig(handler));
            }
        }
    }

    private static void validateTypeRef(String id, Map<String, MixinDescriptor.DefinitionEntry> defs, Method handler) {
        MixinDescriptor.DefinitionEntry d = defs.get(id);
        if (d == null) {
            throw new IllegalStateException(
                "@Expression references undefined type id '" + id + "' on " + sig(handler));
        }
        if (d.type().isEmpty()) {
            throw new IllegalStateException(
                "@Expression uses '" + id + "' as a type but its @Definition does not set type() on " + sig(handler));
        }
    }

    private static void validateMemberRef(String id, Map<String, MixinDescriptor.DefinitionEntry> defs, Method handler, boolean isCall) {
        MixinDescriptor.DefinitionEntry d = defs.get(id);
        if (d == null) {
            throw new IllegalStateException(
                "@Expression references undefined id '" + id + "' on " + sig(handler));
        }
        if (isCall && d.method().isEmpty()) {
            throw new IllegalStateException(
                "@Expression uses '" + id + "' as a call but its @Definition sets field(), not method() on " + sig(handler));
        }
        if (!isCall && d.field().isEmpty()) {
            throw new IllegalStateException(
                "@Expression uses '" + id + "' as a field but its @Definition sets method(), not field() on " + sig(handler));
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
            if (!(a instanceof ExpressionNode.NamedArg(String name))) continue;
            if (paramIndexByName.isEmpty()) {
                throw new IllegalStateException(
                    "@Expression named capture '" + name + "' on " + sig(handler)
                    + " requires the handler to be compiled with -parameters."
                    + " Add `tasks.withType<JavaCompile> { options.compilerArgs.add(\"-parameters\") }`"
                    + " (or the Kotlin equivalent) or use `?` positional captures.");
            }
            if (!paramIndexByName.containsKey(name)) {
                throw new IllegalStateException(
                    "@Expression named capture '" + name + "' on " + sig(handler)
                    + " does not match any handler param. Available: " + paramIndexByName.keySet());
            }
            if (!seen.add(name)) {
                throw new IllegalStateException(
                    "@Expression on " + sig(handler) + " binds the same name '" + name
                    + "' more than once");
            }
        }
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
