package net.echo.hypermixins.agent;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Resolves the set of live locals at a given instruction by walking the method's
 * {@code LocalVariableTable} (preserved by ASM when present in the source class). Used by
 * {@link InjectLocalResolver#siteSlotMap} to pick mid-method locals for {@code @Local}
 * bindings at non-HEAD injection points.
 *
 * <p>Falls back gracefully: if the target was compiled without debug info, the local table
 * is empty and {@link #liveLocalsAt} returns an empty map — the caller surfaces a clear
 * "annotate @Local(index = …) explicitly" error.
 */
final class LocalFrameAnalyzer {

    private final MethodNode method;
    private Map<LabelNode, Integer> labelIndex;

    LocalFrameAnalyzer(MethodNode method) {
        this.method = method;
    }

    /** slot → recorded type at the supplied instruction, sorted by slot for ordinal walks. */
    Map<Integer, Type> liveLocalsAt(AbstractInsnNode insn) {
        if (method.localVariables == null || method.localVariables.isEmpty()) {
            return Map.of();
        }
        int insnIdx = method.instructions.indexOf(insn);
        Map<Integer, Type> out = new TreeMap<>();
        for (LocalVariableNode lv : method.localVariables) {
            int start = labelIndexOf(lv.start);
            int end   = labelIndexOf(lv.end);
            if (insnIdx >= start && insnIdx < end) {
                out.put(lv.index, Type.getType(lv.desc));
            }
        }
        return out;
    }

    private int labelIndexOf(LabelNode label) {
        if (labelIndex == null) {
            labelIndex = new HashMap<>();
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LabelNode ln) {
                    labelIndex.put(ln, method.instructions.indexOf(ln));
                }
            }
        }
        Integer i = labelIndex.get(label);
        return i == null ? -1 : i;
    }
}
