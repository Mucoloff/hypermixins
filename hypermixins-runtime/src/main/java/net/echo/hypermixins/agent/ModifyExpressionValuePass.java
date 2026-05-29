package net.echo.hypermixins.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generalises {@code @ModifyReturnValue} over {@code At.Point} = {@code INVOKE},
 * {@code FIELD}, and {@code CONSTANT} producing instructions. In every case the
 * INVOKESTATIC handler fires immediately after the producing instruction so the handler
 * input is the pushed value and its result replaces it on the stack.
 */
final class ModifyExpressionValuePass {

    private ModifyExpressionValuePass() {}

    static void apply(
        MethodNode method, List<MixinDescriptor.ModifyExpressionValueEntry> mxs, Class<?> mixinClass
    ) {
        if (method.instructions == null) return;
        String mixinInternal = Type.getInternalName(mixinClass);
        Map<MixinDescriptor.ModifyExpressionValueEntry, Integer> matchCount = new HashMap<>();
        List<AbstractInsnNode> snapshot = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext())
            snapshot.add(insn);
        for (AbstractInsnNode insn : snapshot) {
            for (MixinDescriptor.ModifyExpressionValueEntry mx : mxs) {
                if (!method.name.equals(mx.targetMethod())) continue;
                if (!matchesExpressionSite(insn, mx)) continue;
                int count = matchCount.getOrDefault(mx, 0);
                matchCount.put(mx, count + 1);
                if (count != mx.index()) continue;
                method.instructions.insert(insn, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, mixinInternal, mx.handlerName(), mx.handlerDesc(), false));
                break;
            }
        }
    }

    private static boolean matchesExpressionSite(AbstractInsnNode insn, MixinDescriptor.ModifyExpressionValueEntry mx) {
        switch (mx.point()) {
            case INVOKE:
                if (!(insn instanceof MethodInsnNode mi)) return false;
                return DescriptorMatcher.matches(mx.atDesc(), mi.owner + "." + mi.name + mi.desc);
            case FIELD:
                if (!(insn instanceof FieldInsnNode fi)) return false;
                if (fi.getOpcode() != Opcodes.GETFIELD && fi.getOpcode() != Opcodes.GETSTATIC) return false;
                return DescriptorMatcher.matches(mx.atDesc(), fi.owner + "." + fi.name + ":" + fi.desc);
            case CONSTANT: {
                String desc = mx.atDesc();
                int sep = desc.indexOf(':');
                if (sep < 0) return false;
                return ModifyConstantPass.matchesConstantLoad(insn, desc.substring(0, sep), desc.substring(sep + 1));
            }
            default:
                throw new IllegalStateException("@ModifyExpressionValue point " + mx.point() + " not supported");
        }
    }
}
