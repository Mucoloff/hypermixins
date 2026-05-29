package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.At;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * @Inject: rewrites each handler call site for a target method. Handles HEAD / RETURN / TAIL /
 * INVOKE / FIELD / CONSTANT / JUMP / NEW points, resolves @Local captures (by slot or by
 * ordinal type-match), emits the CallbackInfo[Returnable] allocation when cancellable, and
 * performs argsOnly readback after the handler returns.
 */
final class InjectPass {

    private InjectPass() {}

    static void apply(
        ClassNode owner, MethodNode target, List<InjectMapping> injects, String mixinField,
        MixinDescriptor descriptor
    ) {
        if ((target.access & Opcodes.ACC_ABSTRACT) != 0) return;
        Type targetReturn = Type.getReturnType(target.desc);

        Map<String, Map<Integer, MixinDescriptor.InjectLocalEntry>> localEntryByHandler =
            InjectLocalResolver.byHandler(descriptor);

        for (InjectMapping inject : injects) {
            String handlerKey = inject.handler().getName() + Type.getMethodDescriptor(inject.handler());
            Map<Integer, MixinDescriptor.InjectLocalEntry> entryMap =
                localEntryByHandler.getOrDefault(handlerKey, Map.of());
            Set<Integer> argsOnlyParams = InjectLocalResolver.argsOnlyParams(entryMap);
            if (requiresFrameAnalysis(inject.point()) && hasUnresolvedLocal(entryMap)) {
                throw new IllegalStateException(
                    "@Local without explicit index/ordinal is not yet supported at @Inject point "
                    + inject.point() + " (handler " + inject.handler() + "). "
                    + "Set @Local(index = <slot>) on every parameter — type-driven resolution "
                    + "needs ASM Analyzer hookup which has not shipped yet. "
                    + "Tracked in CONTINUE.md backlog.");
            }
            Map<Integer, Integer> slotMap = InjectLocalResolver.slotMap(target, inject.handler(), entryMap);
            At.Shift shift = descriptor.injectShifts().getOrDefault(handlerKey, At.Shift.BEFORE);
            switch (inject.point()) {
                case HEAD -> {
                    AbstractInsnNode first = target.instructions.getFirst();
                    InsnList block = emitInjectCall(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams);
                    if (first == null) target.instructions.add(block);
                    else target.instructions.insertBefore(first, block);
                }
                case TAIL, RETURN -> injectBeforeReturns(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams);
                case INVOKE -> injectAtMatchingSites(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams, shift,
                    insn -> InjectSiteMatcher.matchesInvoke(insn, inject));
                case FIELD -> injectAtMatchingSites(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams, shift,
                    insn -> InjectSiteMatcher.matchesField(insn, inject));
                case CONSTANT -> injectAtMatchingSites(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams, shift,
                    insn -> InjectSiteMatcher.matchesConstant(insn, inject));
                case JUMP -> injectAtMatchingSites(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams, shift,
                    InjectSiteMatcher::isConditionalJump);
                case NEW -> injectAtMatchingSites(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams, shift,
                    insn -> InjectSiteMatcher.matchesNew(insn, inject));
                default -> throw new IllegalStateException("Unsupported @Inject point: " + inject.point());
            }
        }
    }

    private static boolean requiresFrameAnalysis(At.Point point) {
        return switch (point) {
            case HEAD, TAIL, RETURN -> false;
            default -> true;
        };
    }

    private static boolean hasUnresolvedLocal(Map<Integer, MixinDescriptor.InjectLocalEntry> entryMap) {
        for (MixinDescriptor.InjectLocalEntry le : entryMap.values()) {
            if (le.slot() < 0) return true;
        }
        return false;
    }

    private static void injectAtMatchingSites(
        ClassNode owner, MethodNode target, InjectMapping inject,
        String mixinField, Type targetReturn, Map<Integer, Integer> slotMap,
        Set<Integer> argsOnlyParams, At.Shift shift,
        Predicate<AbstractInsnNode> predicate
    ) {
        List<AbstractInsnNode> sites = new ArrayList<>();
        int matchCount = 0;
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!predicate.test(insn)) continue;
            if (inject.index() <= 0 || matchCount == inject.index()) sites.add(insn);
            matchCount++;
            if (inject.index() > 0 && matchCount > inject.index()) break;
        }
        if (sites.isEmpty()) {
            throw new IllegalStateException(
                "@Inject " + inject.point() + " found no matching site for "
                + inject.handler() + " (atDesc=" + inject.atDesc() + ", index=" + inject.index() + ")");
        }
        for (AbstractInsnNode site : sites) {
            InsnList block = emitInjectCall(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams);
            if (shift == At.Shift.AFTER) {
                target.instructions.insert(site, block);
            } else {
                target.instructions.insertBefore(site, block);
            }
        }
    }

    private static void injectBeforeReturns(
        ClassNode owner, MethodNode target, InjectMapping inject, String mixinField, Type targetReturn,
        Map<Integer, Integer> slotMap, Set<Integer> argsOnlyParams
    ) {
        List<AbstractInsnNode> returns = new ArrayList<>();
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) returns.add(insn);
        }
        for (AbstractInsnNode ret : returns) {
            InsnList block = emitInjectCall(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams);
            target.instructions.insertBefore(ret, block);
        }
    }

    private static InsnList emitInjectCall(
        ClassNode owner, MethodNode target, InjectMapping inject,
        String mixinField, Type targetReturn, Map<Integer, Integer> slotMap,
        Set<Integer> argsOnlyParams
    ) {
        InsnList out = new InsnList();
        Class<?> mixinClass = inject.handler().getDeclaringClass();
        String mixinInternal = Type.getInternalName(mixinClass);
        String mixinDesc     = Type.getDescriptor(mixinClass);
        String handlerDesc   = Type.getMethodDescriptor(inject.handler());

        int ciLocal = inject.cancellable() ? CallbackInfoEmitter.allocate(out, target, inject) : -1;

        out.add(new VarInsnNode(Opcodes.ALOAD, 0));
        out.add(new FieldInsnNode(Opcodes.GETFIELD, owner.name, mixinField, mixinDesc));
        out.add(new VarInsnNode(Opcodes.ALOAD, 0));
        Type[] handlerArgs = Type.getArgumentTypes(handlerDesc);
        int captureCount = handlerArgs.length - 1 - (inject.cancellable() ? 1 : 0);
        CaptureEmitter.Result captures = CaptureEmitter.emit(
            out, target, inject, handlerArgs, captureCount, slotMap, argsOnlyParams);

        if (inject.cancellable()) out.add(new VarInsnNode(Opcodes.ALOAD, ciLocal));
        out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, mixinInternal,
            inject.handler().getName(), handlerDesc, false));

        CaptureEmitter.emitArgsOnlyWriteback(out, captures);

        if (inject.cancellable()) CallbackInfoEmitter.emitCancelCheck(out, target, inject, targetReturn, ciLocal);
        return out;
    }
}
