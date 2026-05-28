package net.echo.hypermixins;

import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Original;
import net.echo.hypermixins.annotations.Overwrite;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static net.echo.hypermixins.TransformerTestSupport.key;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Live-dispatch tests: instantiate the transformed target, invoke its rewritten method,
 * and exercise {@link MixinRegistry} enable/disable.
 */
class MixinDispatchTest {

    // ---- fixtures (kept per-test to isolate static MixinRegistry state) ----

    public static class HelloTarget {
        public String hello(String name) { return "original-" + name; }
        public int doubleIt(int x) { return x * 2; }
    }

    @Mixin("net.echo.hypermixins.MixinDispatchTest$HelloTarget")
    public static class HelloMixin {
        @Original("hello") public native String helloOrig(Object self, String name);
        @Overwrite("hello") public String hello(Object self, String name) { return "mixin-" + helloOrig(self, name); }
        @Overwrite("doubleIt") public int doubleIt(Object self, int x) { return x * 3; }
    }

    @AfterEach
    void resetRegistry() { MixinRegistry.clearForTests(); }

    // ---- tests ----

    @Test
    void invokedynamicResolvesAndDispatches() throws Exception {
        Class<?> t = applyMixin(HelloTarget.class, HelloMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        String result = (String) t.getMethod("hello", String.class).invoke(inst, "x");
        assertEquals("mixin-original-x", result);
        assertTrue(MixinRegistry.isActive(key(HelloTarget.class, "hello", "(Ljava/lang/String;)Ljava/lang/String;")));
    }

    @Test
    void disableFallsBackToOriginal() throws Exception {
        Class<?> t = applyMixin(HelloTarget.class, HelloMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        Method hello = t.getMethod("hello", String.class);
        String k = key(HelloTarget.class, "hello", "(Ljava/lang/String;)Ljava/lang/String;");

        // First call to prime call-site (lazy install).
        assertEquals("mixin-original-x", hello.invoke(inst, "x"));

        MixinRegistry.disable(k);
        assertFalse(MixinRegistry.isActive(k));
        assertEquals("original-x", hello.invoke(inst, "x"));

        MixinRegistry.enable(k);
        assertTrue(MixinRegistry.isActive(k));
        assertEquals("mixin-original-x", hello.invoke(inst, "x"));
    }

    @Test
    void enableDisableIdempotent() throws Exception {
        Class<?> t = applyMixin(HelloTarget.class, HelloMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        Method hello = t.getMethod("hello", String.class);
        String k = key(HelloTarget.class, "hello", "(Ljava/lang/String;)Ljava/lang/String;");

        hello.invoke(inst, "y");
        MixinRegistry.disable(k);
        MixinRegistry.disable(k);
        assertEquals("original-y", hello.invoke(inst, "y"));
        MixinRegistry.enable(k);
        MixinRegistry.enable(k);
        assertEquals("mixin-original-y", hello.invoke(inst, "y"));
    }

    @Test
    void originalFallbackUsesMangledHelper() throws Exception {
        Class<?> t = applyMixin(HelloTarget.class, HelloMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();

        Method synthetic = null;
        for (Method m : t.getDeclaredMethods()) {
            if (m.getName().startsWith("__original$hello$")) { synthetic = m; break; }
        }
        assertNotNull(synthetic, "Expected synthetic __original$hello$* trampoline");
        synthetic.setAccessible(true);
        assertEquals("original-x", synthetic.invoke(inst, "x"));
    }

    @Test
    void distinctKeysIndependent() throws Exception {
        Class<?> t = applyMixin(HelloTarget.class, HelloMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        Method hello = t.getMethod("hello", String.class);
        Method doubleIt = t.getMethod("doubleIt", int.class);

        // Prime both call sites.
        assertEquals("mixin-original-z", hello.invoke(inst, "z"));
        assertEquals(15, doubleIt.invoke(inst, 5));

        String khello = key(HelloTarget.class, "hello", "(Ljava/lang/String;)Ljava/lang/String;");
        MixinRegistry.disable(khello);
        assertEquals("original-z", hello.invoke(inst, "z"));
        // doubleIt unaffected.
        assertEquals(15, doubleIt.invoke(inst, 5));
    }
}
