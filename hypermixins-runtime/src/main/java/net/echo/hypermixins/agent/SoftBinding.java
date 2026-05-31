package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.Invoker;
import net.echo.hypermixins.annotations.Shadow;
import net.echo.hypermixins.annotations.Soft;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Helpers for {@code @Soft @Shadow} / {@code @Soft @Invoker} target-absence handling.
 * Probes the target class for the named method and, when absent, generates an
 * {@code UnsupportedOperationException}-throwing trampoline body so the rest of the mixin
 * still loads.
 */
final class SoftBinding {

    private SoftBinding() {}

    /** Try to load the target class via the mixin's loader. Returns null on failure. */
    static Class<?> tryLoadTarget(MixinMapping mapping) {
        try {
            return Class.forName(mapping.getTargetClass(), false, mapping.getMixinClass().getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code handlerName+handlerDesc} keys for every {@code @Soft @Shadow} method on the mixin. */
    static Set<String> collectSoftShadowMethodKeys(Class<?> mixinClass) {
        Set<String> out = new HashSet<>();
        for (Method m : mixinClass.getDeclaredMethods()) {
            if (m.getAnnotation(Shadow.class) == null) continue;
            if (m.getAnnotation(Soft.class) == null) continue;
            out.add(m.getName() + Type.getMethodDescriptor(m));
        }
        return out;
    }

    /** {@code handlerName+handlerDesc} keys for every {@code @Soft @Invoker} method on the mixin. */
    static Set<String> collectSoftInvokerMethodKeys(Class<?> mixinClass) {
        Set<String> out = new HashSet<>();
        for (Method m : mixinClass.getDeclaredMethods()) {
            if (m.getAnnotation(Invoker.class) == null) continue;
            if (m.getAnnotation(Soft.class) == null) continue;
            out.add(m.getName() + Type.getMethodDescriptor(m));
        }
        return out;
    }

    /**
     * True if {@code targetCls} declares (or inherits) a method matching {@code name + desc}.
     * Returns true when {@code targetCls} is null (we couldn't probe — assume present).
     */
    static boolean targetMethodExists(Class<?> targetCls, String name, String desc) {
        if (targetCls == null) return true;
        Type[] argTypes = Type.getArgumentTypes(desc);
        Class<?>[] params = new Class<?>[argTypes.length];
        try {
            for (int i = 0; i < argTypes.length; i++) {
                params[i] = ReflectionProbes.classForType(argTypes[i], targetCls.getClassLoader());
            }
        } catch (Throwable t) {
            return true;
        }
        for (Class<?> c = targetCls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod(name, params);
                return true;
            } catch (NoSuchMethodException ignored) {}
        }
        return false;
    }

    /**
     * Builds {@code throw new UnsupportedOperationException(message);} returning whatever the
     * declared return type demands (push default value first only when needed — UOE makes the
     * IRETURN unreachable anyway, but the verifier wants the stack consistent).
     */
    static InsnList uoeBody(String message, Type returnType) {
        InsnList out = new InsnList();
        out.add(new TypeInsnNode(Opcodes.NEW, "java/lang/UnsupportedOperationException"));
        out.add(new InsnNode(Opcodes.DUP));
        out.add(new LdcInsnNode(message));
        out.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
            "java/lang/UnsupportedOperationException", "<init>", "(Ljava/lang/String;)V", false));
        out.add(new InsnNode(Opcodes.ATHROW));
        return out;
    }
}
