package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.Coerce;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.annotation.Annotation;
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
            out.computeIfAbsent(le.handlerName() + le.handlerDesc(), _ -> new HashMap<>())
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
            boolean coerce = hasCoerceAnnotation(handler, e.getKey());
            Type[] targetArgs = Type.getArgumentTypes(target.desc);
            int slotCursor = 1;
            int seen = 0;
            int resolved = -1;
            int resolvedCount = 0;
            for (Type t : targetArgs) {
                if (typeMatches(t, wanted, coerce, handler.getDeclaringClass().getClassLoader())) {
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
                throw new InjectSignatureMismatch(
                    "@Local of type " + wanted + " not found in target "
                        + target.name + target.desc);
            }
            if (le.ordinal() < 0 && resolvedCount > 1) {
                throw new InjectSignatureMismatch(
                    "@Local of type " + wanted + " is ambiguous (matches " + resolvedCount
                        + " target params) — set index or ordinal on "
                        + handler.getName());
            }
            out.put(e.getKey(), resolved);
        }
        return out;
    }

    /**
     * Frame-driven variant: walks the live locals at {@code site} (as computed by
     * {@link LocalFrameAnalyzer}) instead of the target's incoming parameters. Handles
     * {@code @Local(ordinal} = N) and bare @Local; bare bindings fail on ambiguity, matching the
     * static-param resolver's contract.
     */
    static Map<Integer, Integer> siteSlotMap(
        MethodNode target, Method handler, AbstractInsnNode site,
        Map<Integer, MixinDescriptor.InjectLocalEntry> entryMap,
        LocalFrameAnalyzer analyzer
    ) {
        Map<Integer, Integer> out = new HashMap<>();
        if (entryMap.isEmpty()) return out;
        Map<Integer, Type> slotTypes = analyzer.liveLocalsAt(site);
        if (slotTypes.isEmpty()) {
            throw new InjectSignatureMismatch(
                "@Local site-frame analysis found no debug locals on " + target.name + target.desc
                + " — compile with -g or annotate every handler param with @Local(index = <slot>)");
        }
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
            boolean coerce = hasCoerceAnnotation(handler, e.getKey());

            int seen = 0;
            int resolved = -1;
            int resolvedCount = 0;
            // Iterate slots in ascending order so ordinal counts match declaration order.
            for (Map.Entry<Integer, Type> st : slotTypes.entrySet()) {
                if (!typeMatches(st.getValue(), wanted, coerce, handler.getDeclaringClass().getClassLoader())) continue;
                if (le.ordinal() >= 0) {
                    if (seen == le.ordinal()) { resolved = st.getKey(); break; }
                    seen++;
                } else {
                    if (resolvedCount == 0) resolved = st.getKey();
                    resolvedCount++;
                }
            }
            if (resolved < 0) {
                throw new InjectSignatureMismatch(
                    "@Local of type " + wanted + " not live at injection site in "
                        + target.name + target.desc);
            }
            if (le.ordinal() < 0 && resolvedCount > 1) {
                throw new InjectSignatureMismatch(
                    "@Local of type " + wanted + " is ambiguous (matches " + resolvedCount
                        + " live locals at injection site) — set index or ordinal on "
                        + handler.getName());
            }
            out.put(e.getKey(), resolved);
        }
        return out;
    }

    private static boolean hasCoerceAnnotation(Method handler, int paramIndex) {
        Annotation[][] paramAnns = handler.getParameterAnnotations();
        if (paramIndex < 0 || paramIndex >= paramAnns.length) return false;
        for (Annotation a : paramAnns[paramIndex]) {
            if (a instanceof Coerce) return true;
        }
        return false;
    }

    /**
     * Strict type equality by default. When {@code coerce} is true AND both types are
     * reference (object) types, accept the match when the slot's runtime class is assignable
     * to the handler's declared parameter class. Primitives ignore {@code coerce}.
     */
    private static boolean typeMatches(Type slot, Type wanted, boolean coerce, ClassLoader loader) {
        if (slot.equals(wanted)) return true;
        if (!coerce) return false;
        if (slot.getSort() != Type.OBJECT && slot.getSort() != Type.ARRAY) return false;
        if (wanted.getSort() != Type.OBJECT && wanted.getSort() != Type.ARRAY) return false;
        try {
            Class<?> slotCls = Class.forName(slot.getClassName(), false, loader);
            Class<?> wantedCls = Class.forName(wanted.getClassName(), false, loader);
            return wantedCls.isAssignableFrom(slotCls);
        } catch (Throwable t) {
            return false;
        }
    }
}
