package net.echo.hypermixins.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Conditionally suppresses a matched INVOKE / FIELD call site. The static handler receives
 * the original (receiver +) args and returns boolean: {@code true} = run the original site,
 * {@code false} = skip and push the default value for the original return type so the rest of
 * the body stays stack-consistent.
 *
 * <p>Stack layout per site:
 * <pre>
 *     ... args on stack ...
 *  -> stash args into temp locals (preserve order)
 *  -> push args back for handler call
 *  -> INVOKESTATIC handler  // ()Z
 *  -> IFEQ skipLabel
 *  -> push args back for original INVOKE
 *  -> original INVOKE
 *  -> GOTO endLabel
 * skipLabel:
 *  -> push default(returnType)
 * endLabel:
 *     ... result on stack ...
 * </pre>
 */
final class WrapWithConditionPass {

    private WrapWithConditionPass() {}

    static void apply(MethodNode method, Map<String, List<MixinDescriptor.WrapConditionEntry>> entriesByDesc, Class<?> mixinClass) {
        if (method.instructions == null || entriesByDesc.isEmpty()) return;
        String mixinInternal = Type.getInternalName(mixinClass);
        List<MixinDescriptor.WrapConditionEntry> wildcards = entriesByDesc.entrySet().stream()
            .filter(e -> e.getKey().indexOf('*') >= 0 || e.getKey().startsWith("regex:"))
            .flatMap(e -> e.getValue().stream())
            .toList();

        Map<MixinDescriptor.WrapConditionEntry, Integer> matchCount = new HashMap<>();
        List<AbstractInsnNode> snapshot = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            snapshot.add(insn);
        }
        for (AbstractInsnNode insn : snapshot) {
            String key;
            if (insn instanceof MethodInsnNode mi) {
                key = mi.owner + "." + mi.name + mi.desc;
            } else if (insn instanceof FieldInsnNode fi) {
                key = fi.owner + "." + fi.name + ":" + fi.desc;
            } else continue;

            List<MixinDescriptor.WrapConditionEntry> direct = entriesByDesc.get(key);
            if (direct == null && wildcards.isEmpty()) continue;
            List<MixinDescriptor.WrapConditionEntry> effective = direct != null ? direct : List.of();
            if (!wildcards.isEmpty()) {
                List<MixinDescriptor.WrapConditionEntry> all = new ArrayList<>(effective);
                for (MixinDescriptor.WrapConditionEntry w : wildcards) {
                    if (DescriptorMatcher.matches(w.invokeDesc(), key)) all.add(w);
                }
                effective = all;
            }
            for (MixinDescriptor.WrapConditionEntry wc : effective) {
                if (!method.name.equals(wc.targetMethod())) continue;
                int count = matchCount.getOrDefault(wc, 0);
                matchCount.put(wc, count + 1);
                // index <= 0: wrap every match; index > 0: wrap only the (1-based) index-th.
                if (wc.index() > 0 && count + 1 != wc.index()) continue;
                wrap(method, insn, wc, mixinInternal);
                break;
            }
        }
    }

    private static void wrap(MethodNode method, AbstractInsnNode site, MixinDescriptor.WrapConditionEntry wc, String mixinInternal) {
        // Effective call shape: if MethodInsnNode and non-static / non-INVOKESPECIAL → handler also takes receiver.
        Type[] effectiveArgs;
        Type effectiveReturn;
        boolean hasReceiver;
        if (site instanceof MethodInsnNode mi) {
            Type[] callArgs = Type.getArgumentTypes(mi.desc);
            effectiveReturn = Type.getReturnType(mi.desc);
            hasReceiver = mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                       || mi.getOpcode() == Opcodes.INVOKEINTERFACE
                       || mi.getOpcode() == Opcodes.INVOKESPECIAL;
            if (hasReceiver) {
                effectiveArgs = new Type[callArgs.length + 1];
                effectiveArgs[0] = Type.getObjectType(mi.owner);
                System.arraycopy(callArgs, 0, effectiveArgs, 1, callArgs.length);
            } else {
                effectiveArgs = callArgs;
            }
        } else {
            FieldInsnNode fi = (FieldInsnNode) site;
            int op = fi.getOpcode();
            hasReceiver = op == Opcodes.PUTFIELD || op == Opcodes.GETFIELD;
            Type fieldType = Type.getType(fi.desc);
            switch (op) {
                case Opcodes.GETFIELD -> {
                    effectiveArgs = new Type[]{ Type.getObjectType(fi.owner) };
                    effectiveReturn = fieldType;
                }
                case Opcodes.GETSTATIC -> {
                    effectiveArgs = new Type[0];
                    effectiveReturn = fieldType;
                }
                case Opcodes.PUTFIELD -> {
                    effectiveArgs = new Type[]{ Type.getObjectType(fi.owner), fieldType };
                    effectiveReturn = Type.VOID_TYPE;
                }
                case Opcodes.PUTSTATIC -> {
                    effectiveArgs = new Type[]{ fieldType };
                    effectiveReturn = Type.VOID_TYPE;
                }
                default -> { return; }
            }
        }

        int[] argLocals = new int[effectiveArgs.length];
        int slotCursor = method.maxLocals;
        for (int i = 0; i < effectiveArgs.length; i++) {
            argLocals[i] = slotCursor;
            slotCursor += effectiveArgs[i].getSize();
        }
        method.maxLocals = slotCursor;

        InsnList block = new InsnList();
        // 1. Stash args into temp locals (reverse order, stack top = last arg).
        for (int i = effectiveArgs.length - 1; i >= 0; i--) {
            block.add(new VarInsnNode(effectiveArgs[i].getOpcode(Opcodes.ISTORE), argLocals[i]));
        }
        // 2. Push args back for handler call.
        for (int i = 0; i < effectiveArgs.length; i++) {
            block.add(new VarInsnNode(effectiveArgs[i].getOpcode(Opcodes.ILOAD), argLocals[i]));
        }
        block.add(new MethodInsnNode(Opcodes.INVOKESTATIC, mixinInternal,
            wc.handlerName(), wc.handlerDesc(), false));
        // 3. Branch on the boolean.
        LabelNode skipLabel = new LabelNode();
        LabelNode endLabel = new LabelNode();
        block.add(new JumpInsnNode(Opcodes.IFEQ, skipLabel));
        // 4. True branch — push args back for the original site.
        for (int i = 0; i < effectiveArgs.length; i++) {
            block.add(new VarInsnNode(effectiveArgs[i].getOpcode(Opcodes.ILOAD), argLocals[i]));
        }
        method.instructions.insertBefore(site, block);
        // 5. Original site stays; after it, GOTO endLabel.
        InsnList afterSite = new InsnList();
        afterSite.add(new JumpInsnNode(Opcodes.GOTO, endLabel));
        afterSite.add(skipLabel);
        // 6. Skip branch — push default for return type.
        afterSite.add(defaultValue(effectiveReturn));
        afterSite.add(endLabel);
        method.instructions.insert(site, afterSite);
    }

    private static AbstractInsnNode defaultValue(Type returnType) {
        return switch (returnType.getSort()) {
            case Type.VOID -> new InsnNode(Opcodes.NOP);
            case Type.LONG -> new InsnNode(Opcodes.LCONST_0);
            case Type.FLOAT -> new InsnNode(Opcodes.FCONST_0);
            case Type.DOUBLE -> new InsnNode(Opcodes.DCONST_0);
            case Type.OBJECT, Type.ARRAY -> new InsnNode(Opcodes.ACONST_NULL);
            default -> new InsnNode(Opcodes.ICONST_0);
        };
    }
}
