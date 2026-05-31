package net.echo.hypermixins.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Reverse-walk helpers for {@link ExpressionMatcher}. Identifies the source of each value
 * pushed onto the stack just before a matched INVOKE / FIELD instruction, so {@code ?}
 * placeholders can be bound to target-local slots.
 *
 * <p>The walker only recognises clean producer patterns ({@code *LOAD slot}). Any other
 * instruction shape returns {@code -1} so the caller can fall back (or fail). The walker
 * does not attempt full data-flow analysis.
 *
 * <p>Skipped producers: for an INVOKE with N args, the stack just before the INVOKE looks like
 * {@code [receiver?, arg0, arg1, ..., argN-1]}. Producer instructions appear in reverse on
 * the bytecode stream: the last-emitted is {@code argN-1}'s source, walking back finds
 * {@code argN-2}, ..., {@code arg0}, then the receiver. Since v2 only consumes clean
 * single-instruction loads, each step is exactly one {@link VarInsnNode}.
 */
final class ExpressionStackWalker {

    private ExpressionStackWalker() {}

    /**
     * Returns the slot loaded by the immediate predecessor that supplied stack position
     * {@code stackOffset} (0 = top of stack just before {@code site}). The walk skips
     * {@code stackOffset} clean loads. Returns {@code -1} when any step is not a
     * {@code *LOAD}.
     */
    static int findLoadSlotForStackPosition(AbstractInsnNode site, int stackOffset) {
        AbstractInsnNode cursor = site.getPrevious();
        for (int i = 0; i < stackOffset; i++) {
            cursor = skipToProducer(cursor);
            if (cursor == null) return -1;
            cursor = cursor.getPrevious();
        }
        AbstractInsnNode producer = skipToProducer(cursor);
        if (!(producer instanceof VarInsnNode v)) return -1;
        if (!isLoadOpcode(v.getOpcode())) return -1;
        return v.var;
    }

    /**
     * Returns the producer instruction at stack position {@code stackOffset} just before
     * {@code site}. Each step assumes a single-instruction producer (a clean {@code *LOAD},
     * a sub-{@code INVOKE} return value, a field access, etc.). Returns {@code null} when the
     * walk runs off the beginning of the method body.
     */
    static AbstractInsnNode findProducerAt(AbstractInsnNode site, int stackOffset) {
        AbstractInsnNode cursor = site.getPrevious();
        for (int i = 0; i < stackOffset; i++) {
            cursor = skipToProducer(cursor);
            if (cursor == null) return null;
            cursor = cursor.getPrevious();
        }
        return skipToProducer(cursor);
    }

    /**
     * Returns {@code true} when the producer of stack position {@code stackOffset} just
     * before {@code site} is {@code ALOAD 0}. Used to enforce {@code this} receiver semantics
     * on {@code Chained(ThisRef, ...)} matches.
     */
    static boolean producerIsAload0(AbstractInsnNode site, int stackOffset) {
        AbstractInsnNode cursor = site.getPrevious();
        for (int i = 0; i < stackOffset; i++) {
            cursor = skipToProducer(cursor);
            if (cursor == null) return false;
            cursor = cursor.getPrevious();
        }
        AbstractInsnNode producer = skipToProducer(cursor);
        if (!(producer instanceof VarInsnNode v)) return false;
        return v.getOpcode() == Opcodes.ALOAD && v.var == 0;
    }

    /** Walks past metadata pseudo-nodes (labels, line numbers, frames) to the next real insn. */
    private static AbstractInsnNode skipToProducer(AbstractInsnNode n) {
        while (n != null && isPseudo(n)) n = n.getPrevious();
        return n;
    }

    private static boolean isPseudo(AbstractInsnNode n) {
        int t = n.getType();
        return t == AbstractInsnNode.LABEL
            || t == AbstractInsnNode.LINE
            || t == AbstractInsnNode.FRAME;
    }

    private static boolean isLoadOpcode(int op) {
        return op == Opcodes.ILOAD || op == Opcodes.LLOAD || op == Opcodes.FLOAD
            || op == Opcodes.DLOAD || op == Opcodes.ALOAD;
    }
}
