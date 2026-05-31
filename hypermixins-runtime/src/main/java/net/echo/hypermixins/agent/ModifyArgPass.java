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
 * {@code @ModifyArg:} rewrites a single argument at a matched INVOKE site. Last-arg case is a bare
 * INVOKESTATIC; middle-arg cases stash trailing args into temp locals so the handler sees its
 * target on top of stack, then restore.
 */
final class ModifyArgPass {

    private ModifyArgPass() {}

    static void apply(
        MethodNode method, Map<String, List<MixinDescriptor.ModifyArgEntry>> masByDesc, Class<?> mixinClass
    ) {
        if (method.instructions == null) return;
        String mixinInternal = Type.getInternalName(mixinClass);
        List<MixinDescriptor.ModifyArgEntry> wildcards = masByDesc.entrySet().stream()
            .filter(e -> e.getKey().indexOf('*') >= 0 || e.getKey().startsWith("regex:"))
            .flatMap(e -> e.getValue().stream())
            .toList();
        Map<MixinDescriptor.ModifyArgEntry, Integer> matchCount = new HashMap<>();
        List<AbstractInsnNode> snapshot = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext())
            snapshot.add(insn);
        for (AbstractInsnNode insn : snapshot) {
            if (!(insn instanceof MethodInsnNode mi)) continue;
            String key = mi.owner + "." + mi.name + mi.desc;
            List<MixinDescriptor.ModifyArgEntry> candidates = masByDesc.get(key);
            if (candidates == null && wildcards.isEmpty()) continue;
            List<MixinDescriptor.ModifyArgEntry> effective = candidates != null ? candidates : List.of();
            if (!wildcards.isEmpty()) {
                List<MixinDescriptor.ModifyArgEntry> all = new ArrayList<>(effective);
                for (MixinDescriptor.ModifyArgEntry w : wildcards) {
                    if (DescriptorMatcher.matches(w.invokeDesc(), key)) all.add(w);
                }
                effective = all;
            }
            for (MixinDescriptor.ModifyArgEntry ma : effective) {
                if (!method.name.equals(ma.targetMethod())) continue;
                int count = matchCount.getOrDefault(ma, 0);
                matchCount.put(ma, count + 1);
                if (count != 0) continue;
                Type[] argTypes = Type.getArgumentTypes(mi.desc);
                int argIdx = ma.argIndex();
                if (argIdx < 0 || argIdx >= argTypes.length) {
                    throw new IllegalStateException(
                        "@ModifyArg handler " + ma.handlerName() + ma.handlerDesc()
                        + " in " + mixinClass.getName()
                        + ": index=" + argIdx + " out of range for site " + key);
                }
                if (argIdx == argTypes.length - 1) {
                    method.instructions.insertBefore(mi, new MethodInsnNode(
                        Opcodes.INVOKESTATIC, mixinInternal, ma.handlerName(), ma.handlerDesc(), false));
                } else {
                    int[] argLocals = new int[argTypes.length];
                    int cursor = method.maxLocals;
                    for (int i = 0; i < argTypes.length; i++) {
                        argLocals[i] = cursor;
                        cursor += argTypes[i].getSize();
                    }
                    method.maxLocals = cursor;
                    InsnList block = new InsnList();
                    for (int i = argTypes.length - 1; i > argIdx; i--) {
                        block.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ISTORE), argLocals[i]));
                    }
                    block.add(new MethodInsnNode(Opcodes.INVOKESTATIC, mixinInternal,
                        ma.handlerName(), ma.handlerDesc(), false));
                    for (int i = argIdx + 1; i < argTypes.length; i++) {
                        block.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ILOAD), argLocals[i]));
                    }
                    method.instructions.insertBefore(mi, block);
                }
                break;
            }
        }
    }
}
