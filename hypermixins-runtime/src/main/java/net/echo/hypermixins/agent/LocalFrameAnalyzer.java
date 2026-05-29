package net.echo.hypermixins.agent;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.Map;
import java.util.TreeMap;

/**
 * Computes per-instruction {@link Frame}s for {@code @Inject} non-HEAD point resolution.
 * Used by {@link InjectLocalResolver#siteSlotMap} to walk live local types at the injection
 * instruction so handler parameters can bind to mid-method locals, not just incoming params.
 *
 * <p>Analysis runs at most once per (owner, target) pair and is cached for the lifetime of
 * this analyzer instance, which lives for the duration of a single {@link InjectPass#apply}
 * invocation.
 */
final class LocalFrameAnalyzer {

    private final String ownerInternal;
    private final MethodNode method;
    private Frame<BasicValue>[] frames;

    LocalFrameAnalyzer(String ownerInternal, MethodNode method) {
        this.ownerInternal = ownerInternal;
        this.method = method;
    }

    /**
     * Resolves the frame at the given instruction or null if analysis failed (likely a
     * malformed method body or unsupported opcode mix — caller should fall back to the
     * static-param resolver and surface a clear error).
     */
    Frame<BasicValue> frameAt(AbstractInsnNode insn) {
        if (frames == null) {
            Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
            try {
                frames = analyzer.analyze(ownerInternal, method);
            } catch (AnalyzerException e) {
                return null;
            }
        }
        int idx = method.instructions.indexOf(insn);
        if (idx < 0 || idx >= frames.length) return null;
        return frames[idx];
    }

    /**
     * Maps each frame slot's recorded type. Slots with no value (uninitialised) are absent.
     */
    static Map<Integer, Type> slotTypes(Frame<BasicValue> frame) {
        Map<Integer, Type> out = new TreeMap<>();
        for (int i = 0; i < frame.getLocals(); i++) {
            BasicValue v = frame.getLocal(i);
            if (v == null) continue;
            Type t = v.getType();
            if (t == null) continue;
            out.put(i, t);
        }
        return out;
    }
}
