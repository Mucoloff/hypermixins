package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.At;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
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

    private static final String CB_INFO_INTERNAL = "net/echo/hypermixins/annotations/CallbackInfo";
    private static final String CB_RET_INTERNAL  = "net/echo/hypermixins/annotations/CallbackInfoReturnable";

    private InjectPass() {}

    static void apply(
        ClassNode owner, MethodNode target, List<InjectMapping> injects, String mixinField,
        MixinDescriptor descriptor
    ) {
        if ((target.access & Opcodes.ACC_ABSTRACT) != 0) return;
        Type targetReturn = Type.getReturnType(target.desc);

        Map<String, Map<Integer, MixinDescriptor.InjectLocalEntry>> localEntryByHandler = new HashMap<>();
        for (MixinDescriptor.InjectLocalEntry le : descriptor.injectLocals()) {
            localEntryByHandler
                .computeIfAbsent(le.handlerName() + le.handlerDesc(), k -> new HashMap<>())
                .put(le.paramIndex(), le);
        }

        for (InjectMapping inject : injects) {
            String handlerKey = inject.handler().getName() + Type.getMethodDescriptor(inject.handler());
            Map<Integer, MixinDescriptor.InjectLocalEntry> entryMap =
                localEntryByHandler.getOrDefault(handlerKey, Map.of());
            Set<Integer> argsOnlyParams = new HashSet<>();
            for (Map.Entry<Integer, MixinDescriptor.InjectLocalEntry> e : entryMap.entrySet()) {
                if (e.getValue().argsOnly()) argsOnlyParams.add(e.getKey());
            }
            Map<Integer, Integer> slotMap = new HashMap<>();
            for (Map.Entry<Integer, MixinDescriptor.InjectLocalEntry> e : entryMap.entrySet()) {
                MixinDescriptor.InjectLocalEntry le = e.getValue();
                if (le.slot() >= 0) {
                    slotMap.put(e.getKey(), le.slot());
                } else {
                    Type[] handlerArgs = Type.getArgumentTypes(Type.getMethodDescriptor(inject.handler()));
                    Type wantedRaw = handlerArgs[e.getKey()];
                    Type wanted = le.argsOnly() && wantedRaw.getSort() == Type.ARRAY
                        ? wantedRaw.getElementType()
                        : wantedRaw;
                    Type[] targetArgs = Type.getArgumentTypes(target.desc);
                    int slotCursor = 1;
                    int seen = 0;
                    int resolved = -1;
                    int resolvedCount = 0;
                    for (Type t : targetArgs) {
                        if (t.equals(wanted)) {
                            if (le.ordinal() >= 0) {
                                if (seen == le.ordinal()) { resolved = slotCursor; break; }
                                seen++;
                            } else {
                                if (resolvedCount == 0) resolved = slotCursor;
                                resolvedCount++;
                            }
                        }
                        slotCursor += t.getSize();
                    }
                    if (resolved < 0) {
                        throw new IllegalStateException(
                            "@Local of type " + wanted + " not found in target "
                                + target.name + target.desc);
                    }
                    if (le.ordinal() < 0 && resolvedCount > 1) {
                        throw new IllegalStateException(
                            "@Local of type " + wanted + " is ambiguous (matches " + resolvedCount
                                + " target params) — set index or ordinal on "
                                + inject.handler().getName());
                    }
                    slotMap.put(e.getKey(), resolved);
                }
            }
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
                    insn -> matchesInvoke(insn, inject));
                case FIELD -> injectAtMatchingSites(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams, shift,
                    insn -> matchesField(insn, inject));
                case CONSTANT -> injectAtMatchingSites(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams, shift,
                    insn -> matchesConstant(insn, inject));
                case JUMP -> injectAtMatchingSites(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams, shift,
                    InjectPass::isConditionalJump);
                case NEW -> injectAtMatchingSites(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams, shift,
                    insn -> matchesNew(insn, inject));
                default -> throw new IllegalStateException("Unsupported @Inject point: " + inject.point());
            }
        }
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

    private static boolean matchesInvoke(AbstractInsnNode insn, InjectMapping inject) {
        if (!(insn instanceof MethodInsnNode mi)) return false;
        return DescriptorMatcher.matches(inject.atDesc(), mi.owner + "." + mi.name + mi.desc);
    }

    private static boolean matchesField(AbstractInsnNode insn, InjectMapping inject) {
        if (!(insn instanceof FieldInsnNode fi)) return false;
        return DescriptorMatcher.matches(inject.atDesc(), fi.owner + "." + fi.name + ":" + fi.desc);
    }

    private static boolean matchesConstant(AbstractInsnNode insn, InjectMapping inject) {
        if (!(insn instanceof LdcInsnNode ldc)) return false;
        String desc = inject.atDesc();
        int sep = desc.indexOf(':');
        if (sep < 0) return false;
        String type = desc.substring(0, sep);
        String value = desc.substring(sep + 1);
        Object cst = ldc.cst;
        return switch (type) {
            case "I" -> cst instanceof Integer i && i == Integer.parseInt(value);
            case "J" -> cst instanceof Long l && l == Long.parseLong(value);
            case "F" -> cst instanceof Float f && f == Float.parseFloat(value);
            case "D" -> cst instanceof Double d && d == Double.parseDouble(value);
            case "Ljava/lang/String;" -> cst instanceof String s && s.equals(value);
            default -> false;
        };
    }

    private static boolean matchesNew(AbstractInsnNode insn, InjectMapping inject) {
        if (!(insn instanceof TypeInsnNode tn)) return false;
        if (tn.getOpcode() != Opcodes.NEW) return false;
        return tn.desc.equals(inject.atDesc());
    }

    private static boolean isConditionalJump(AbstractInsnNode insn) {
        if (!(insn instanceof JumpInsnNode jump)) return false;
        int op = jump.getOpcode();
        return op != Opcodes.GOTO && op != Opcodes.JSR;
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

        int ciLocal = -1;
        if (inject.cancellable()) {
            ciLocal = target.maxLocals;
            target.maxLocals += 1;
            out.add(new LdcInsnNode(inject.targetMethod()));
            if (inject.returnable()) {
                out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CB_RET_INTERNAL, "of",
                    "(Ljava/lang/String;)L" + CB_RET_INTERNAL + ";", false));
            } else {
                out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CB_INFO_INTERNAL, "of",
                    "(Ljava/lang/String;)L" + CB_INFO_INTERNAL + ";", false));
            }
            out.add(new VarInsnNode(Opcodes.ASTORE, ciLocal));
        }

        out.add(new VarInsnNode(Opcodes.ALOAD, 0));
        out.add(new FieldInsnNode(Opcodes.GETFIELD, owner.name, mixinField, mixinDesc));
        out.add(new VarInsnNode(Opcodes.ALOAD, 0));
        Type[] handlerArgs = Type.getArgumentTypes(handlerDesc);
        int captureCount = handlerArgs.length - 1 - (inject.cancellable() ? 1 : 0);
        Map<Integer, Integer> argsOnlyArrayLocals = new HashMap<>();
        Map<Integer, Integer> argsOnlySourceSlots = new HashMap<>();
        Map<Integer, Type> argsOnlyElementTypes = new HashMap<>();
        if (captureCount > 0) {
            Type[] targetArgs = Type.getArgumentTypes(target.desc);
            int positionalSlot = 1;
            int positionalIdx = 0;
            for (int i = 0; i < captureCount; i++) {
                Type expected = handlerArgs[1 + i];
                boolean argsOnly = argsOnlyParams.contains(1 + i);
                Integer forcedSlot = slotMap.get(1 + i);
                if (argsOnly) {
                    if (forcedSlot == null) {
                        throw new IllegalStateException(
                            "@Local(argsOnly = true) requires a resolvable source slot for handler "
                                + inject.handler() + " param " + i);
                    }
                    if (expected.getSort() != Type.ARRAY) {
                        throw new IllegalStateException(
                            "@Local(argsOnly = true) handler param must be a single-element array — got "
                                + expected + " on " + inject.handler());
                    }
                    Type element = expected.getElementType();
                    out.add(new InsnNode(Opcodes.ICONST_1));
                    if (element.getSort() == Type.OBJECT || element.getSort() == Type.ARRAY) {
                        out.add(new TypeInsnNode(Opcodes.ANEWARRAY, element.getInternalName()));
                    } else {
                        out.add(new IntInsnNode(Opcodes.NEWARRAY, Bytecode.newarrayOperandForPrimitive(element)));
                    }
                    int arrLocal = target.maxLocals;
                    target.maxLocals += 1;
                    out.add(new InsnNode(Opcodes.DUP));
                    out.add(new InsnNode(Opcodes.ICONST_0));
                    out.add(new VarInsnNode(element.getOpcode(Opcodes.ILOAD), forcedSlot));
                    out.add(new InsnNode(element.getOpcode(Opcodes.IASTORE)));
                    out.add(new InsnNode(Opcodes.DUP));
                    out.add(new VarInsnNode(Opcodes.ASTORE, arrLocal));
                    argsOnlyArrayLocals.put(1 + i, arrLocal);
                    argsOnlySourceSlots.put(1 + i, forcedSlot);
                    argsOnlyElementTypes.put(1 + i, element);
                    continue;
                }
                if (forcedSlot != null) {
                    out.add(new VarInsnNode(expected.getOpcode(Opcodes.ILOAD), forcedSlot));
                    continue;
                }
                if (positionalIdx >= targetArgs.length) {
                    throw new IllegalStateException(
                        "@Inject handler " + inject.handler() +
                        " declares positional capture beyond target arity for " +
                        target.name + target.desc);
                }
                Type actual = targetArgs[positionalIdx];
                if (!expected.equals(actual)) {
                    throw new IllegalStateException(
                        "@Inject handler " + inject.handler() + " param " + i +
                        " type " + expected + " does not match target " + target.name + target.desc +
                        " param " + positionalIdx + " type " + actual);
                }
                out.add(new VarInsnNode(actual.getOpcode(Opcodes.ILOAD), positionalSlot));
                positionalSlot += actual.getSize();
                positionalIdx++;
            }
        }
        if (inject.cancellable()) {
            out.add(new VarInsnNode(Opcodes.ALOAD, ciLocal));
        }
        out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, mixinInternal,
            inject.handler().getName(), handlerDesc, false));

        for (Map.Entry<Integer, Integer> e : argsOnlyArrayLocals.entrySet()) {
            int paramIdx = e.getKey();
            int arrLocal = e.getValue();
            int sourceSlot = argsOnlySourceSlots.get(paramIdx);
            Type element = argsOnlyElementTypes.get(paramIdx);
            out.add(new VarInsnNode(Opcodes.ALOAD, arrLocal));
            out.add(new InsnNode(Opcodes.ICONST_0));
            out.add(new InsnNode(element.getOpcode(Opcodes.IALOAD)));
            out.add(new VarInsnNode(element.getOpcode(Opcodes.ISTORE), sourceSlot));
        }

        if (inject.cancellable()) {
            LabelNode notCancelled = new LabelNode();
            out.add(new VarInsnNode(Opcodes.ALOAD, ciLocal));
            String cancelOwner = inject.returnable() ? CB_RET_INTERNAL : CB_INFO_INTERNAL;
            out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, cancelOwner, "isCancelled", "()Z", false));
            out.add(new JumpInsnNode(Opcodes.IFEQ, notCancelled));
            if (inject.returnable()) {
                out.add(new VarInsnNode(Opcodes.ALOAD, ciLocal));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CB_RET_INTERNAL, "getReturnValue",
                    "()Ljava/lang/Object;", false));
                Bytecode.unboxOrCast(out, targetReturn);
                out.add(new InsnNode(targetReturn.getOpcode(Opcodes.IRETURN)));
            } else {
                if (targetReturn.getSort() != Type.VOID) {
                    throw new IllegalStateException(
                        "@Inject(cancellable=true) with CallbackInfo on non-void target " +
                        target.name + target.desc + " — use CallbackInfoReturnable");
                }
                out.add(new InsnNode(Opcodes.RETURN));
            }
            out.add(notCancelled);
        }
        return out;
    }
}
