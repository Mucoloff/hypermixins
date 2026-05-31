package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.At;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.function.Predicate;

/**
 * Resolves a {@code @Slice(from, to)} pair into an inclusive [start, end] instruction-index
 * window over a target {@link MethodNode}. {@code from = @At(HEAD)} or empty {@code desc}
 * means "no lower bound"; {@code to = @At(HEAD)} or empty {@code desc} means "no upper
 * bound". Returns null when no slice is needed (default annotation, both ends open).
 */
final class SliceWindow {

    private SliceWindow() {}

    static int[] resolve(MethodNode target, At from, At to) {
        if (from == null && to == null) return null;
        boolean fromOpen = from == null || isOpenBound(from);
        boolean toOpen   = to == null   || isOpenBound(to);
        if (fromOpen && toOpen) return null;

        int total = target.instructions.size();
        int startIdx = 0;
        int endIdx = total - 1;
        if (!fromOpen) {
            startIdx = findFirstIndex(target, atPredicate(from));
            if (startIdx < 0) throw new IllegalStateException(
                "@Slice.from() found no matching instruction in " + target.name + target.desc);
        }
        if (!toOpen) {
            endIdx = findLastIndex(target, atPredicate(to));
            if (endIdx < 0) throw new IllegalStateException(
                "@Slice.to() found no matching instruction in " + target.name + target.desc);
        }
        return new int[]{startIdx, endIdx};
    }

    private static boolean isOpenBound(At at) {
        return at.point() == At.Point.HEAD && at.desc().isEmpty();
    }

    private static Predicate<AbstractInsnNode> atPredicate(At at) {
        return switch (at.point()) {
            case INVOKE -> insn -> insn instanceof MethodInsnNode mi
                && DescriptorMatcher.matches(at.desc(), mi.owner + "." + mi.name + mi.desc);
            case FIELD -> insn -> insn instanceof FieldInsnNode fi
                && DescriptorMatcher.matches(at.desc(), fi.owner + "." + fi.name + ":" + fi.desc);
            case CONSTANT -> insn -> matchesConstant(insn, at.desc());
            case NEW -> insn -> insn instanceof TypeInsnNode tn
                && tn.getOpcode() == Opcodes.NEW && tn.desc.equals(at.desc());
            case JUMP -> insn -> insn instanceof JumpInsnNode j
                && j.getOpcode() != Opcodes.GOTO && j.getOpcode() != Opcodes.JSR;
            default -> insn -> false;
        };
    }

    private static boolean matchesConstant(AbstractInsnNode insn, String desc) {
        if (!(insn instanceof LdcInsnNode ldc)) return false;
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

    private static int findFirstIndex(MethodNode target, Predicate<AbstractInsnNode> predicate) {
        int idx = 0;
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (predicate.test(insn)) return idx;
            idx++;
        }
        return -1;
    }

    private static int findLastIndex(MethodNode target, Predicate<AbstractInsnNode> predicate) {
        int idx = target.instructions.size() - 1;
        for (AbstractInsnNode insn = target.instructions.getLast(); insn != null; insn = insn.getPrevious()) {
            if (predicate.test(insn)) return idx;
            idx--;
        }
        return -1;
    }
}
