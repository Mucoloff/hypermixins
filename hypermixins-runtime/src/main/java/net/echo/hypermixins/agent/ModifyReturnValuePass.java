package net.echo.hypermixins.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Inserts an {@code INVOKESTATIC} call to the {@code @ModifyReturnValue} handler immediately
 * after each matched INVOKE site, so the handler's input is the pushed return value and its
 * result replaces it on the stack.
 */
final class ModifyReturnValuePass {

    private ModifyReturnValuePass() {}

    static void apply(
        MethodNode method, Map<String, List<MixinDescriptor.ModifyReturnValueEntry>> mrvByDesc,
        Class<?> mixinClass
    ) {
        if (method.instructions == null) return;
        List<MixinDescriptor.ModifyReturnValueEntry> wildcards = mrvByDesc.entrySet().stream()
            .filter(e -> e.getKey().indexOf('*') >= 0 || e.getKey().startsWith("regex:"))
            .flatMap(e -> e.getValue().stream())
            .toList();
        Map<MixinDescriptor.ModifyReturnValueEntry, Integer> matchCount = new HashMap<>();
        List<AbstractInsnNode> snapshot = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext())
            snapshot.add(insn);
        String mixinInternal = Type.getInternalName(mixinClass);

        for (AbstractInsnNode insn : snapshot) {
            if (!(insn instanceof MethodInsnNode mi)) continue;
            String invokeKey = mi.owner + "." + mi.name + mi.desc;
            List<MixinDescriptor.ModifyReturnValueEntry> candidates = mrvByDesc.get(invokeKey);
            if (candidates == null && wildcards.isEmpty()) continue;
            List<MixinDescriptor.ModifyReturnValueEntry> effective = candidates != null ? candidates : List.of();
            if (!wildcards.isEmpty()) {
                List<MixinDescriptor.ModifyReturnValueEntry> all = new ArrayList<>(effective);
                for (MixinDescriptor.ModifyReturnValueEntry w : wildcards) {
                    if (DescriptorMatcher.matches(w.invokeDesc(), invokeKey)) all.add(w);
                }
                effective = all;
            }
            for (MixinDescriptor.ModifyReturnValueEntry mrv : effective) {
                if (!method.name.equals(mrv.targetMethod())) continue;
                int count = matchCount.getOrDefault(mrv, 0);
                matchCount.put(mrv, count + 1);
                if (count != mrv.index()) continue;
                method.instructions.insert(mi, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, mixinInternal, mrv.handlerName(), mrv.handlerDesc(), false));
                break;
            }
        }
    }
}
