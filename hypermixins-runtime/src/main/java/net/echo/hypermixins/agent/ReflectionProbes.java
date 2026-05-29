package net.echo.hypermixins.agent;

import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Classpath probes for the {@link MixinDescriptor#fromAnnotations} fallback path. Flags
 * {@code @Shadow}/{@code @Invoker} targets visible as private and {@code @Overwrite}/
 * {@code @Original} targets visible as static. Mirrors the KSP-side TargetProbes in the
 * processor module.
 */
final class ReflectionProbes {

    private ReflectionProbes() {}

    static Set<String> privateShadowTargets(
        Class<?> mixinClass, String targetInternal,
        List<MixinDescriptor.ShadowEntry> shadows, List<MixinDescriptor.InvokerEntry> invokers
    ) {
        Set<String> out = new HashSet<>();
        Class<?> targetCls;
        try {
            targetCls = Class.forName(targetInternal.replace('/', '.'), false, mixinClass.getClassLoader());
        } catch (Throwable t) {
            return out;
        }
        for (MixinDescriptor.ShadowEntry sh : shadows) {
            recordIfPrivate(targetCls, sh.targetName(), dropFirstArg(sh.handlerDesc()), out);
        }
        for (MixinDescriptor.InvokerEntry iv : invokers) {
            recordIfPrivate(targetCls, iv.targetName(), dropFirstArg(iv.handlerDesc()), out);
        }
        return out;
    }

    static Set<String> staticTargetMethods(
        Class<?> mixinClass, String targetInternal,
        List<MixinDescriptor.OriginalEntry> originals, List<MixinDescriptor.OverwriteEntry> overwrites
    ) {
        Set<String> out = new HashSet<>();
        Class<?> targetCls;
        try {
            targetCls = Class.forName(targetInternal.replace('/', '.'), false, mixinClass.getClassLoader());
        } catch (Throwable t) {
            return out;
        }
        Set<String> pairs = new HashSet<>();
        for (MixinDescriptor.OriginalEntry oe : originals) {
            pairs.add(oe.targetName() + dropFirstArg(oe.handlerDesc()));
        }
        for (MixinDescriptor.OverwriteEntry oe : overwrites) {
            pairs.add(oe.targetName() + oe.targetDesc());
        }
        for (String pair : pairs) {
            int paren = pair.indexOf('(');
            if (paren < 0) continue;
            String name = pair.substring(0, paren);
            String desc = pair.substring(paren);
            try {
                Type[] paramTypes = Type.getArgumentTypes(desc);
                Class<?>[] params = new Class<?>[paramTypes.length];
                for (int i = 0; i < paramTypes.length; i++) {
                    params[i] = classForType(paramTypes[i], targetCls.getClassLoader());
                }
                Method m = targetCls.getDeclaredMethod(name, params);
                if (Modifier.isStatic(m.getModifiers())) out.add(pair);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    private static void recordIfPrivate(Class<?> targetCls, String name, String desc, Set<String> out) {
        try {
            Type[] paramTypes = Type.getArgumentTypes(desc);
            Class<?>[] params = new Class<?>[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                params[i] = classForType(paramTypes[i], targetCls.getClassLoader());
            }
            Method m = targetCls.getDeclaredMethod(name, params);
            if (Modifier.isPrivate(m.getModifiers())) out.add(name + desc);
        } catch (Throwable ignored) {}
    }

    static String dropFirstArg(String desc) {
        Type[] all = Type.getArgumentTypes(desc);
        if (all.length == 0) return desc;
        Type ret = Type.getReturnType(desc);
        Type[] rest = Arrays.copyOfRange(all, 1, all.length);
        return Type.getMethodDescriptor(ret, rest);
    }

    static Class<?> classForType(Type t, ClassLoader cl) throws ClassNotFoundException {
        return switch (t.getSort()) {
            case Type.BOOLEAN -> boolean.class;
            case Type.BYTE -> byte.class;
            case Type.CHAR -> char.class;
            case Type.SHORT -> short.class;
            case Type.INT -> int.class;
            case Type.LONG -> long.class;
            case Type.FLOAT -> float.class;
            case Type.DOUBLE -> double.class;
            case Type.VOID -> void.class;
            case Type.ARRAY -> Class.forName(t.getDescriptor().replace('/', '.'), false, cl);
            default -> Class.forName(t.getClassName(), false, cl);
        };
    }
}
