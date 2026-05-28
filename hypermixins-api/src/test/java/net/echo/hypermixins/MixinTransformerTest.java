package net.echo.hypermixins;

import net.echo.hypermixins.agent.MixinMapping;
import net.echo.hypermixins.agent.MixinTransformer;
import net.echo.hypermixins.agent.MixinTransformer.SafeClassWriter;
import net.echo.hypermixins.api.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for MixinTransformer using an in-memory ClassLoader.
 * No Instrumentation needed — we call the transformer directly on raw bytecode.
 */
class MixinTransformerTest {

    // ---- fixtures ----

    /** Target class to be overwritten. */
    public static class SimpleTarget {
        public String hello(String name) {
            return "original-" + name;
        }

        public int doubleIt(int x) {
            return x * 2;
        }

        public long sumLong(long a, long b) {
            return a + b;
        }
    }

    @Mixin("net.echo.hypermixins.MixinTransformerTest$SimpleTarget")
    public static class SimpleMixin {

        @Original("hello")
        public native String helloOrig(Object self, String name);

        @Overwrite("hello")
        public String hello(Object self, String name) {
            return "mixin-" + helloOrig(self, name);
        }

        @Overwrite("doubleIt")
        public int doubleIt(Object self, int x) {
            return x * 3; // overwrite: triple instead of double
        }
    }

    @Mixin("net.echo.hypermixins.MixinTransformerTest$SimpleTarget")
    public static class LongArgMixin {
        @Overwrite("sumLong")
        public long sumLong(Object self, long a, long b) {
            return a + b + 1; // adds 1 extra
        }
    }

    // ---- helper ----

    private static Class<?> applyMixin(Class<?> targetClass, Class<?> mixinClass) throws Exception {
        MixinMapping mapping = new MixinMapping(mixinClass);
        MixinTransformer transformer = new MixinTransformer(List.of(mapping));

        // transform target
        String targetInternal = targetClass.getName().replace('.', '/');
        byte[] original = loadBytecode(targetClass);
        byte[] transformed = transformer.transform(null, targetClass.getClassLoader(),
            targetInternal, null, null, original);
        assertNotNull(transformed, "Transformer returned null — no mapping matched");

        // verify bytecode is valid
        verifyBytecode(transformed);

        // transform mixin (rewrite @Original stubs)
        String mixinInternal = mixinClass.getName().replace('.', '/');
        byte[] mixinOriginal = loadBytecode(mixinClass);
        byte[] mixinTransformed = transformer.transform(null, mixinClass.getClassLoader(),
            mixinInternal, null, null, mixinOriginal);
        assertNotNull(mixinTransformed, "Mixin transformer returned null");
        verifyBytecode(mixinTransformed);

        // load both via in-memory loader
        InMemoryClassLoader loader = new InMemoryClassLoader(targetClass.getClassLoader());
        loader.define(mixinClass.getName(), mixinTransformed);
        return loader.define(targetClass.getName(), transformed);
    }

    private static byte[] loadBytecode(Class<?> cls) throws Exception {
        String resource = cls.getName().replace('.', '/') + ".class";
        try (var is = cls.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(is, "Cannot load bytecode for " + cls);
            return is.readAllBytes();
        }
    }

    private static void verifyBytecode(byte[] bytecode) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        try {
            CheckClassAdapter.verify(new ClassReader(bytecode), false, pw);
        } catch (Exception e) {
            fail("ASM CheckClassAdapter threw: " + e.getMessage() + "\n" + sw);
        }
        String output = sw.toString();
        if (!output.isBlank()) {
            fail("Bytecode verification errors:\n" + output);
        }
    }

    static class InMemoryClassLoader extends ClassLoader {
        InMemoryClassLoader(ClassLoader parent) { super(parent); }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    // ---- tests ----

    @Test
    void overwriteReplacesBody() throws Exception {
        Class<?> transformed = applyMixin(SimpleTarget.class, SimpleMixin.class);
        Object instance = transformed.getDeclaredConstructor().newInstance();
        String result = (String) transformed.getMethod("hello", String.class).invoke(instance, "world");
        // mixin wraps original: "mixin-original-world"
        assertEquals("mixin-original-world", result);
    }

    @Test
    void overwriteInt() throws Exception {
        Class<?> transformed = applyMixin(SimpleTarget.class, SimpleMixin.class);
        Object instance = transformed.getDeclaredConstructor().newInstance();
        int result = (int) transformed.getMethod("doubleIt", int.class).invoke(instance, 5);
        assertEquals(15, result); // 5*3
    }

    @Test
    void longDoubleSlotHandled() throws Exception {
        Class<?> transformed = applyMixin(SimpleTarget.class, LongArgMixin.class);
        Object instance = transformed.getDeclaredConstructor().newInstance();
        long result = (long) transformed.getMethod("sumLong", long.class, long.class).invoke(instance, 3L, 4L);
        assertEquals(8L, result); // 3+4+1
    }

    @Test
    void mangledNameNoCollisionOnDifferentDescriptors() {
        // Two different descriptors must produce different mangled names
        String n1 = MixinTransformer.mangledName("method", "(Ljava/lang/String;)V");
        String n2 = MixinTransformer.mangledName("method", "(I)V");
        assertNotEquals(n1, n2);
    }

    @Test
    void mangledNameDeterministic() {
        String a = MixinTransformer.mangledName("foo", "(I)V");
        String b = MixinTransformer.mangledName("foo", "(I)V");
        assertEquals(a, b);
    }
}
