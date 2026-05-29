package net.echo.hypermixins.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Predicates that decide whether an instruction matches one of {@code @At}'s non-HEAD points
 * (INVOKE / FIELD / CONSTANT / NEW / JUMP). Each variant decodes the annotation's
 * {@code @At#desc} format documented at its respective method.
 */
final class InjectSiteMatcher {

    private InjectSiteMatcher() {}

    static boolean matchesInvoke(AbstractInsnNode insn, InjectMapping inject) {
        if (!(insn instanceof MethodInsnNode mi)) return false;
        return DescriptorMatcher.matches(inject.atDesc(), mi.owner + "." + mi.name + mi.desc);
    }

    static boolean matchesField(AbstractInsnNode insn, InjectMapping inject) {
        if (!(insn instanceof FieldInsnNode fi)) return false;
        return DescriptorMatcher.matches(inject.atDesc(), fi.owner + "." + fi.name + ":" + fi.desc);
    }

    /** Constant match: {@code "<type>:<value>"}, types I / J / F / D / Ljava/lang/String;. */
    static boolean matchesConstant(AbstractInsnNode insn, InjectMapping inject) {
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

    static boolean matchesNew(AbstractInsnNode insn, InjectMapping inject) {
        if (!(insn instanceof TypeInsnNode tn)) return false;
        if (tn.getOpcode() != Opcodes.NEW) return false;
        return tn.desc.equals(inject.atDesc());
    }

    static boolean isConditionalJump(AbstractInsnNode insn) {
        if (!(insn instanceof JumpInsnNode jump)) return false;
        int op = jump.getOpcode();
        return op != Opcodes.GOTO && op != Opcodes.JSR;
    }
}
