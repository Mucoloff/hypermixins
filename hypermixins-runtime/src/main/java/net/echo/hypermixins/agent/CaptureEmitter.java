package net.echo.hypermixins.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Emits the handler-argument capture sequence for {@code @Inject.emitInjectCall}:
 * each handler parameter is either an {@code @Local(argsOnly = true)} single-element array,
 * an explicit slot from {@code slotMap}, or a positional copy of the target's incoming
 * parameter. {@code @Local(argsOnly)} captures double back as a writeback pass after the
 * handler runs — {@link Result} carries the bookkeeping that {@link #emitArgsOnlyWriteback}
 * needs.
 */
final class CaptureEmitter {

    private CaptureEmitter() {}

    static final class Result {
        final Map<Integer, Integer> arrayLocals = new HashMap<>();
        final Map<Integer, Integer> sourceSlots = new HashMap<>();
        final Map<Integer, Type> elementTypes = new HashMap<>();
    }

    static Result emit(
        InsnList out, MethodNode target, InjectMapping inject,
        Type[] handlerArgs, int captureCount,
        Map<Integer, Integer> slotMap, Set<Integer> argsOnlyParams
    ) {
        Result r = new Result();
        if (captureCount <= 0) return r;
        Method handler = inject.handler();
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
                            + handler + " param " + i);
                }
                if (expected.getSort() != Type.ARRAY) {
                    throw new IllegalStateException(
                        "@Local(argsOnly = true) handler param must be a single-element array — got "
                            + expected + " on " + handler);
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
                r.arrayLocals.put(1 + i, arrLocal);
                r.sourceSlots.put(1 + i, forcedSlot);
                r.elementTypes.put(1 + i, element);
                continue;
            }
            if (forcedSlot != null) {
                out.add(new VarInsnNode(expected.getOpcode(Opcodes.ILOAD), forcedSlot));
                continue;
            }
            if (positionalIdx >= targetArgs.length) {
                throw new IllegalStateException(
                    "@Inject handler " + handler
                    + " declares positional capture beyond target arity for "
                    + target.name + target.desc);
            }
            Type actual = targetArgs[positionalIdx];
            if (!expected.equals(actual)) {
                throw new IllegalStateException(
                    "@Inject handler " + handler + " param " + i
                    + " type " + expected + " does not match target " + target.name + target.desc
                    + " param " + positionalIdx + " type " + actual);
            }
            out.add(new VarInsnNode(actual.getOpcode(Opcodes.ILOAD), positionalSlot));
            positionalSlot += actual.getSize();
            positionalIdx++;
        }
        return r;
    }

    /** Loads {@code array[0]} from each argsOnly capture back into its source local slot. */
    static void emitArgsOnlyWriteback(InsnList out, Result r) {
        for (Map.Entry<Integer, Integer> e : r.arrayLocals.entrySet()) {
            int paramIdx = e.getKey();
            int arrLocal = e.getValue();
            int sourceSlot = r.sourceSlots.get(paramIdx);
            Type element = r.elementTypes.get(paramIdx);
            out.add(new VarInsnNode(Opcodes.ALOAD, arrLocal));
            out.add(new InsnNode(Opcodes.ICONST_0));
            out.add(new InsnNode(element.getOpcode(Opcodes.IALOAD)));
            out.add(new VarInsnNode(element.getOpcode(Opcodes.ISTORE), sourceSlot));
        }
    }
}
