package net.echo.hypermixins;

import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Original;
import net.echo.hypermixins.annotations.Overwrite;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static net.echo.hypermixins.TransformerTestSupport.key;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies hot-swap and full-unregister semantics on {@link MixinRegistry}.
 * <p>
 * Reload contract:
 * <ul>
 *   <li>{@code uninstall} steers the call-site to the {@code __original$} trampoline.</li>
 *   <li>{@code reinstall} flips the call-site to a fresh {@link MethodHandle} without
 *       retransforming the target.</li>
 * </ul>
 */
public class MixinReloadTest {

    public static class Target {
        public String hello(String name) { return "original-" + name; }
    }

    @Mixin("net.echo.hypermixins.MixinReloadTest$Target")
    public static class V1Mixin {
        @Original("hello") public native String orig(Object self, String name);
        @Overwrite("hello") public String hello(Object self, String name) { return "v1-" + orig(self, name); }
    }

    @AfterEach
    void reset() { MixinRegistry.clearForTests(); }

    @Test
    void uninstallFallsBackThenReinstallSwaps() throws Throwable {
        Class<?> t = applyMixin(Target.class, V1Mixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        Method hello = t.getMethod("hello", String.class);
        String k = key(Target.class, "hello", "(Ljava/lang/String;)Ljava/lang/String;");

        // Prime the call-site via lazy bootstrap.
        assertEquals("v1-original-x", hello.invoke(inst, "x"));

        // Uninstall → call hits the original via __original$ trampoline.
        MixinRegistry.uninstall(k);
        assertEquals("original-x", hello.invoke(inst, "x"));

        // Reinstall with a constant MethodHandle to simulate a hot-reloaded mixin handler.
        MethodHandle swapped = MethodHandles.constant(String.class, "v2-hot-swapped");
        swapped = MethodHandles.dropArguments(swapped, 0, t, String.class);
        MixinRegistry.reinstall(k, swapped);
        assertEquals("v2-hot-swapped", hello.invoke(inst, "y"));
    }

    @Test
    void disableEnableRoundTrip() throws Throwable {
        Class<?> t = applyMixin(Target.class, V1Mixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        Method hello = t.getMethod("hello", String.class);
        String k = key(Target.class, "hello", "(Ljava/lang/String;)Ljava/lang/String;");

        // Prime lazy bootstrap so ORIGINALS + MIXINS are populated.
        assertEquals("v1-original-x", hello.invoke(inst, "x"));
        assertTrue(MixinRegistry.isActive(k));

        // disable → original behaviour, call-site flipped off the mixin.
        MixinRegistry.disable(k);
        assertFalse(MixinRegistry.isActive(k));
        assertEquals("original-x", hello.invoke(inst, "x"));

        // enable → mixin handler restored.
        MixinRegistry.enable(k);
        assertTrue(MixinRegistry.isActive(k));
        assertEquals("v1-original-x", hello.invoke(inst, "x"));
    }

    @Test
    void installDisableEnableUninstallChain() throws Throwable {
        Class<?> t = applyMixin(Target.class, V1Mixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        Method hello = t.getMethod("hello", String.class);
        String k = key(Target.class, "hello", "(Ljava/lang/String;)Ljava/lang/String;");

        // active
        assertEquals("v1-original-x", hello.invoke(inst, "x"));
        // disable → original
        MixinRegistry.disable(k);
        assertEquals("original-x", hello.invoke(inst, "x"));
        // enable → active
        MixinRegistry.enable(k);
        assertEquals("v1-original-x", hello.invoke(inst, "x"));
        // uninstall → permanently original; enable is now a no-op (handler gone).
        MixinRegistry.uninstall(k);
        assertEquals("original-x", hello.invoke(inst, "x"));
        MixinRegistry.enable(k);
        assertFalse(MixinRegistry.isActive(k));
        assertEquals("original-x", hello.invoke(inst, "x"));
    }
}
