package net.echo.hypermixins.agent;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Resolves {@code @Local} handler-parameter bindings into per-target-method slot indices.
 * Two cases:
 * <ul>
 *   <li>{@code slot >= 0}: literal slot from the annotation, passed straight through.</li>
 *   <li>{@code slot &lt; 0}: type-driven resolution — walks the target's incoming params in
 *       declaration order, matches by element type ({@code argsOnly} unwraps {@code T[]} →
 *       {@code T}), and picks the {@code ordinal}-th match. Bare (no-ordinal) bindings demand
 *       a unique match and throw on ambiguity.</li>
 * </ul>
 */
final class InjectLocalResolver {

    private InjectLocalResolver() {}

    static Map<String, Map<Integer, MixinDescriptor.InjectLocalEntry>> byHandler(
        MixinDescriptor descriptor
    ) {
        Map<String, Map<Integer, MixinDescriptor.InjectLocalEntry>> out = new HashMap<>();
        for (MixinDescriptor.InjectLocalEntry le : descriptor.injectLocals()) {
            out.computeIfAbsent(le.handlerName() + le.handlerDesc(), k -> new HashMap<>())
                .put(le.paramIndex(), le);
        }
        return out;
    }

    static Set<Integer> argsOnlyParams(Map<Integer, MixinDescriptor.InjectLocalEntry> entryMap) {
        Set<Integer> out = new HashSet<>();
        for (Map.Entry<Integer, MixinDescriptor.InjectLocalEntry> e : entryMap.entrySet()) {
            if (e.getValue().argsOnly()) out.add(e.getKey());
        }
        return out;
    }

    static Map<Integer, Integer> slotMap(
        MethodNode target, Method handler,
        Map<Integer, MixinDescriptor.InjectLocalEntry> entryMap
    ) {
        Map<Integer, Integer> out = new HashMap<>();
        for (Map.Entry<Integer, MixinDescriptor.InjectLocalEntry> e : entryMap.entrySet()) {
            MixinDescriptor.InjectLocalEntry le = e.getValue();
            if (le.slot() >= 0) {
                out.put(e.getKey(), le.slot());
                continue;
            }
            Type[] handlerArgs = Type.getArgumentTypes(Type.getMethodDescriptor(handler));
            Type wantedRaw = handlerArgs[e.getKey()];
            Type wanted = le.argsOnly() && wantedRaw.getSort() == Type.ARRAY
                ? wantedRaw.getElementType()
                : wantedRaw;
            Type[] targetArgs = Type.getArgumentTypes(target.desc);
            int slotCursor = 1;
            int seen = 0;
            int resolved = -1;
            int resolvedCount = 0;
            for (Type t : targetArgs) {
                if (t.equals(wanted)) {
                    if (le.ordinal() >= 0) {
                        if (seen == le.ordinal()) { resolved = slotCursor; break; }
                        seen++;
                    } else {
                        if (resolvedCount == 0) resolved = slotCursor;
                        resolvedCount++;
                    }
                }
                slotCursor += t.getSize();
            }
            if (resolved < 0) {
                throw new IllegalStateException(
                    "@Local of type " + wanted + " not found in target "
                        + target.name + target.desc);
            }
            if (le.ordinal() < 0 && resolvedCount > 1) {
                throw new IllegalStateException(
                    "@Local of type " + wanted + " is ambiguous (matches " + resolvedCount
                        + " target params) — set index or ordinal on "
                        + handler.getName());
            }
            out.put(e.getKey(), resolved);
        }
        return out;
    }
}
