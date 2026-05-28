package net.echo.hypermixins;

import net.echo.hypermixins.agent.MixinMapping;
import net.echo.hypermixins.agent.MixinTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared helpers for in-memory transform-and-load test fixtures. No JVM Instrumentation needed.
 */
final class TransformerTestSupport {

    private TransformerTestSupport() {}

    /** Transforms {@code targetClass} + {@code mixinClass}, returns the transformed target {@code Class<?>}. */
    static Class<?> applyMixin(Class<?> targetClass, Class<?> mixinClass) throws Exception {
        return applyMixin(targetClass, mixinClass, new HashMap<>());
    }

    /** Same as {@link #applyMixin(Class, Class)} but reuses a class cache so additional classes can be defined. */
    static Class<?> applyMixin(Class<?> targetClass, Class<?> mixinClass, Map<String, Class<?>> cache) throws Exception {
        MixinMapping mapping = new MixinMapping(mixinClass);
        MixinTransformer transformer = new MixinTransformer(List.of(mapping));

        String targetInternal = targetClass.getName().replace('.', '/');
        byte[] originalTarget = loadBytecode(targetClass);
        byte[] transformedTarget = transformer.transform(null, targetClass.getClassLoader(),
            targetInternal, null, null, originalTarget);
        assertNotNull(transformedTarget, "Transformer returned null for target " + targetClass);
        verifyBytecode(transformedTarget);

        String mixinInternal = mixinClass.getName().replace('.', '/');
        byte[] originalMixin = loadBytecode(mixinClass);
        byte[] transformedMixin = transformer.transform(null, mixinClass.getClassLoader(),
            mixinInternal, null, null, originalMixin);
        assertNotNull(transformedMixin, "Transformer returned null for mixin " + mixinClass);
        verifyBytecode(transformedMixin);

        InMemoryClassLoader loader = new InMemoryClassLoader(targetClass.getClassLoader());
        Class<?> mixinDefined = loader.define(mixinClass.getName(), transformedMixin);
        cache.put(mixinClass.getName(), mixinDefined);
        Class<?> targetDefined = loader.define(targetClass.getName(), transformedTarget);
        cache.put(targetClass.getName(), targetDefined);
        return targetDefined;
    }

    static byte[] loadBytecode(Class<?> cls) throws Exception {
        String resource = cls.getName().replace('.', '/') + ".class";
        try (var is = cls.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(is, "Cannot load bytecode for " + cls);
            return is.readAllBytes();
        }
    }

    static void verifyBytecode(byte[] bytecode) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        try {
            CheckClassAdapter.verify(new ClassReader(bytecode), false, pw);
        } catch (Exception e) {
            fail("ASM CheckClassAdapter threw: " + e.getMessage() + "\n" + sw);
        }
        String output = sw.toString();
        if (!output.isBlank()) fail("Bytecode verification errors:\n" + output);
    }

    /** Key format used by MixinRegistry: {@code "owner/Internal#methodName(params)ret"}. */
    static String key(Class<?> targetClass, String methodName, String desc) {
        return targetClass.getName().replace('.', '/') + "#" + methodName + desc;
    }

    static final class InMemoryClassLoader extends ClassLoader {
        InMemoryClassLoader(ClassLoader parent) { super(parent); }
        Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }
}
