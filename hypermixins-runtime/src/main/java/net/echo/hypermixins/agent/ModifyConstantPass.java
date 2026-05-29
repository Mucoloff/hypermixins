package net.echo.hypermixins.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks the target method for constant loads matching any {@code @ModifyConstant} entry's
 * (type, value, target, index) tuple. Inserts an INVOKESTATIC handler call immediately after
 * the matched load so the handler input is the pushed value and its result replaces it.
 */
final class ModifyConstantPass {

    private ModifyConstantPass() {}

    static void apply(
        MethodNode method, List<MixinDescriptor.ModifyConstantEntry> mcs, Class<?> mixinClass
    ) {
        if (method.instructions == null) return;
        String mixinInternal = Type.getInternalName(mixinClass);
        Map<MixinDescriptor.ModifyConstantEntry, Integer> matchCount = new HashMap<>();
        List<AbstractInsnNode> snapshot = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext())
            snapshot.add(insn);

        for (AbstractInsnNode insn : snapshot) {
            for (MixinDescriptor.ModifyConstantEntry mc : mcs) {
                if (!method.name.equals(mc.targetMethod())) continue;
                if (!matchesConstantLoad(insn, mc.type(), mc.value())) continue;
                int count = matchCount.getOrDefault(mc, 0);
                matchCount.put(mc, count + 1);
                if (count != mc.index()) continue;
                method.instructions.insert(insn, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, mixinInternal, mc.handlerName(), mc.handlerDesc(), false));
                break;
            }
        }
    }

    /**
     * Decodes the constant produced by every supported load instruction and tests it against
     * the typed encoding used by {@code @ModifyConstant.Constant} ({@code I:42},
     * {@code Ljava/lang/String;:hello}, etc.). Reused by {@code @ModifyExpressionValue} when
     * its {@code At.Point} = {@code CONSTANT}.
     */
    static boolean matchesConstantLoad(AbstractInsnNode insn, String type, String value) {
        int op = insn.getOpcode();
        switch (type) {
            case "I" -> {
                int wanted = Integer.parseInt(value);
                if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return wanted == (op - Opcodes.ICONST_0);
                if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)
                    return wanted == ((IntInsnNode) insn).operand;
                if (op == Opcodes.LDC && insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer i)
                    return i == wanted;
                return false;
            }
            case "J" -> {
                long wanted = Long.parseLong(value);
                if (op == Opcodes.LCONST_0) return wanted == 0L;
                if (op == Opcodes.LCONST_1) return wanted == 1L;
                if (op == Opcodes.LDC && insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long l)
                    return l == wanted;
                return false;
            }
            case "F" -> {
                float wanted = Float.parseFloat(value);
                if (op == Opcodes.FCONST_0) return wanted == 0f;
                if (op == Opcodes.FCONST_1) return wanted == 1f;
                if (op == Opcodes.FCONST_2) return wanted == 2f;
                if (op == Opcodes.LDC && insn instanceof LdcInsnNode ldc && ldc.cst instanceof Float f)
                    return f == wanted;
                return false;
            }
            case "D" -> {
                double wanted = Double.parseDouble(value);
                if (op == Opcodes.DCONST_0) return wanted == 0d;
                if (op == Opcodes.DCONST_1) return wanted == 1d;
                if (op == Opcodes.LDC && insn instanceof LdcInsnNode ldc && ldc.cst instanceof Double d)
                    return d == wanted;
                return false;
            }
            case "Ljava/lang/String;" -> {
                return op == Opcodes.LDC && insn instanceof LdcInsnNode ldc
                    && ldc.cst instanceof String s && s.equals(value);
            }
            default -> { return false; }
        }
    }
}
