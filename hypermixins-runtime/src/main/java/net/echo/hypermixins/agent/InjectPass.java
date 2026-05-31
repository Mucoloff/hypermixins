package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Inject;
import net.echo.hypermixins.annotations.Slice;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
            At.Shift shift = descriptor.injectShifts().getOrDefault(handlerKey, At.Shift.BEFORE);
            boolean useAnalyzer = requiresFrameAnalysis(inject.point()) && hasUnresolvedLocal(entryMap);
            LocalFrameAnalyzer analyzer = useAnalyzer ? new LocalFrameAnalyzer(target) : null;
            // The static slot map fits HEAD / TAIL / RETURN (incoming target params); for
            // non-HEAD points with bare @Local the analyzer handles every entry, so the static
            // map would error out on type lookups that only exist mid-method — skip it.
            Map<Integer, Integer> staticSlotMap = useAnalyzer
                ? Map.of()
                : InjectLocalResolver.slotMap(target, inject.handler(), entryMap);
            switch (inject.point()) {
                case HEAD -> {
                    AbstractInsnNode first = target.instructions.getFirst();
                    InsnList block = emitInjectCall(owner, target, inject, mixinField, targetReturn, staticSlotMap, argsOnlyParams);
                    if (first == null) target.instructions.add(block);
                    else target.instructions.insertBefore(first, block);
                }
                case TAIL, RETURN -> injectBeforeReturns(owner, target, inject, mixinField, targetReturn, staticSlotMap, argsOnlyParams, analyzer, entryMap);
                case INVOKE -> injectAtMatchingSites(owner, target, inject, mixinField, targetReturn, staticSlotMap, argsOnlyParams, shift, analyzer, entryMap,
                    insn -> InjectSiteMatcher.matchesInvoke(insn, inject));
                case FIELD -> injectAtMatchingSites(owner, target, inject, mixinField, targetReturn, staticSlotMap, argsOnlyParams, shift, analyzer, entryMap,
                    insn -> InjectSiteMatcher.matchesField(insn, inject));
                case CONSTANT -> injectAtMatchingSites(owner, target, inject, mixinField, targetReturn, staticSlotMap, argsOnlyParams, shift, analyzer, entryMap,
                    insn -> InjectSiteMatcher.matchesConstant(insn, inject));
                case JUMP -> injectAtMatchingSites(owner, target, inject, mixinField, targetReturn, staticSlotMap, argsOnlyParams, shift, analyzer, entryMap,
                    InjectSiteMatcher::isConditionalJump);
                case NEW -> injectAtMatchingSites(owner, target, inject, mixinField, targetReturn, staticSlotMap, argsOnlyParams, shift, analyzer, entryMap,
                    insn -> InjectSiteMatcher.matchesNew(insn, inject));
                default -> throw new IllegalStateException("Unsupported @Inject point: " + inject.point());
            }
        }
    }

    private static boolean requiresFrameAnalysis(At.Point point) {
        // HEAD's incoming-param resolution is always correct (no mid-method locals exist
        // at the top of the body). Every other point may see mid-method locals so route
        // unresolved @Local entries through the LocalVariableTable.
        return point != At.Point.HEAD;
    }

    private static boolean hasUnresolvedLocal(Map<Integer, MixinDescriptor.InjectLocalEntry> entryMap) {
        for (MixinDescriptor.InjectLocalEntry le : entryMap.values()) {
            if (le.slot() < 0) return true;
        }
        return false;
    }

    private static void injectAtMatchingSites(
        ClassNode owner, MethodNode target, InjectMapping inject,
        String mixinField, Type targetReturn, Map<Integer, Integer> staticSlotMap,
        Set<Integer> argsOnlyParams, At.Shift shift,
        LocalFrameAnalyzer analyzer,
        Map<Integer, MixinDescriptor.InjectLocalEntry> entryMap,
        Predicate<AbstractInsnNode> predicate
    ) {
        Slice slice = inject.handler().getAnnotation(Slice.class);
        int[] window = slice != null ? SliceWindow.resolve(target, slice.from(), slice.to()) : null;
        List<AbstractInsnNode> sites = new ArrayList<>();
        int matchCount = 0;
        int insnIdx = 0;
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext(), insnIdx++) {
            if (window != null && (insnIdx < window[0] || insnIdx > window[1])) continue;
            if (!predicate.test(insn)) continue;
            if (inject.index() <= 0 || matchCount == inject.index()) sites.add(insn);
            matchCount++;
            if (inject.index() > 0 && matchCount > inject.index()) break;
        }
        Inject injectAnn = inject.handler().getAnnotation(Inject.class);
        int totalMatched = sites.size();
        int require = injectAnn != null ? injectAnn.require() : 0;
        int allow = injectAnn != null ? injectAnn.allow() : -1;
        if (require > 0 && totalMatched < require) {
            throw new IllegalStateException(
                "@Inject(require=" + require + ") matched " + totalMatched + " site(s) for "
                + inject.handler() + " (atDesc=" + inject.atDesc() + ")");
        }
        if (allow >= 0 && totalMatched > allow) {
            throw new IllegalStateException(
                "@Inject(allow=" + allow + ") matched " + totalMatched + " site(s) for "
                + inject.handler() + " (atDesc=" + inject.atDesc() + ")");
        }
        if (sites.isEmpty()) {
            throw new IllegalStateException(
                "@Inject " + inject.point() + " found no matching site for "
                + inject.handler() + " (atDesc=" + inject.atDesc() + ", index=" + inject.index() + ")");
        }
        int byOffset = (injectAnn != null && injectAnn.at().shift() == At.Shift.BY) ? injectAnn.at().by() : 0;
        boolean shiftIsBy = injectAnn != null && injectAnn.at().shift() == At.Shift.BY;
        for (AbstractInsnNode site : sites) {
            Map<Integer, Integer> slotMap = analyzer != null
                ? InjectLocalResolver.siteSlotMap(target, inject.handler(), site, entryMap, analyzer)
                : staticSlotMap;
            InsnList block = emitInjectCall(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams);
            if (shift == At.Shift.AFTER) {
                target.instructions.insert(site, block);
                continue;
            }
            if (shiftIsBy) {
                AbstractInsnNode anchor = walkByOffset(site, byOffset);
                if (anchor == null) {
                    throw new IllegalStateException(
                        "@At.Shift.BY(by=" + byOffset + ") walked past the method body for "
                        + inject.handler() + " at " + target.name + target.desc);
                }
                target.instructions.insertBefore(anchor, block);
                continue;
            }
            AbstractInsnNode insertBefore = analyzer != null
                ? findArgsOnlyAnchor(site, slotMap, argsOnlyParams, site)
                : site;
            target.instructions.insertBefore(insertBefore, block);
        }
    }

    private static AbstractInsnNode walkByOffset(AbstractInsnNode site, int by) {
        AbstractInsnNode n = site;
        if (by >= 0) {
            for (int i = 0; i < by && n != null; i++) n = n.getNext();
        } else {
            for (int i = 0; i > by && n != null; i--) n = n.getPrevious();
        }
        return n;
    }

    /**
     * @Local(argsOnly = true) writes back into the source slot only after the handler returns,
     * but the matched site's preceding ILOAD has already pushed the pre-mutation value onto the
     * stack. Scan backward from the site for the earliest *LOAD of any argsOnly source slot and
     * relocate the insertion point there so the writeback lands before the consuming push.
     * Falls back to the site itself when no candidate load is found within the method body.
     */
    private static AbstractInsnNode findArgsOnlyAnchor(
        AbstractInsnNode site, Map<Integer, Integer> slotMap, Set<Integer> argsOnlyParams,
        AbstractInsnNode fallback
    ) {
        if (argsOnlyParams.isEmpty()) return fallback;
        Set<Integer> argsOnlySourceSlots = new HashSet<>();
        for (Integer paramIdx : argsOnlyParams) {
            Integer slot = slotMap.get(paramIdx);
            if (slot != null) argsOnlySourceSlots.add(slot);
        }
        if (argsOnlySourceSlots.isEmpty()) return fallback;
        AbstractInsnNode earliestLoad = null;
        for (AbstractInsnNode n = site.getPrevious(); n != null; n = n.getPrevious()) {
            if (!(n instanceof VarInsnNode v)) continue;
            if (!Bytecode.isLoadOpcode(v.getOpcode())) continue;
            if (argsOnlySourceSlots.contains(v.var)) earliestLoad = n;
        }
        return earliestLoad != null ? earliestLoad : fallback;
    }

    private static void injectBeforeReturns(
        ClassNode owner, MethodNode target, InjectMapping inject, String mixinField, Type targetReturn,
        Map<Integer, Integer> staticSlotMap, Set<Integer> argsOnlyParams,
        LocalFrameAnalyzer analyzer, Map<Integer, MixinDescriptor.InjectLocalEntry> entryMap
    ) {
        List<AbstractInsnNode> returns = new ArrayList<>();
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) returns.add(insn);
        }
        for (AbstractInsnNode ret : returns) {
            Map<Integer, Integer> slotMap = analyzer != null
                ? InjectLocalResolver.siteSlotMap(target, inject.handler(), ret, entryMap, analyzer)
                : staticSlotMap;
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
