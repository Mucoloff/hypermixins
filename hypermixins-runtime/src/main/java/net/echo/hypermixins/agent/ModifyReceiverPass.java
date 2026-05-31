package net.echo.hypermixins.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code @ModifyReceiver:} captures the args of a matched INVOKEVIRTUAL / INVOKEINTERFACE into temp
 * locals (in reverse order so the original push sequence is preserved), invokes the static
 * {@code T(T)} handler on the bare receiver, then pushes the args back.
 */
final class ModifyReceiverPass {

    private ModifyReceiverPass() {}

    static void apply(
        MethodNode method, List<MixinDescriptor.ModifyReceiverEntry> mxr, Class<?> mixinClass
    ) {
        if (method.instructions == null) return;
        String mixinInternal = Type.getInternalName(mixinClass);
        Map<MixinDescriptor.ModifyReceiverEntry, Integer> matchCount = new HashMap<>();
        List<AbstractInsnNode> snapshot = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext())
            snapshot.add(insn);
        for (AbstractInsnNode insn : snapshot) {
            if (!(insn instanceof MethodInsnNode mi)) continue;
            String key = mi.owner + "." + mi.name + mi.desc;
            for (MixinDescriptor.ModifyReceiverEntry mr : mxr) {
                if (!method.name.equals(mr.targetMethod())) continue;
                if (!DescriptorMatcher.matches(mr.invokeDesc(), key)) continue;
                int op = mi.getOpcode();
                if (op != Opcodes.INVOKEVIRTUAL && op != Opcodes.INVOKEINTERFACE) {
                    throw new IllegalStateException(
                        "@ModifyReceiver handler " + mr.handlerName() + mr.handlerDesc()
                        + " in " + mixinClass.getName()
                        + " only applies to virtual / interface call sites — got opcode " + op + " on " + key);
                }
                int count = matchCount.getOrDefault(mr, 0);
                matchCount.put(mr, count + 1);
                if (count != 0) continue;
                Type[] argTypes = Type.getArgumentTypes(mi.desc);
                int[] argLocals = new int[argTypes.length];
                int cursor = method.maxLocals;
                for (int i = 0; i < argTypes.length; i++) {
                    argLocals[i] = cursor;
                    cursor += argTypes[i].getSize();
                }
                method.maxLocals = cursor;
                InsnList block = new InsnList();
                for (int i = argTypes.length - 1; i >= 0; i--) {
                    block.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ISTORE), argLocals[i]));
                }
                block.add(new MethodInsnNode(Opcodes.INVOKESTATIC, mixinInternal,
                    mr.handlerName(), mr.handlerDesc(), false));
                for (int i = 0; i < argTypes.length; i++) {
                    block.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ILOAD), argLocals[i]));
                }
                method.instructions.insertBefore(insn, block);
                break;
            }
        }
    }
}
