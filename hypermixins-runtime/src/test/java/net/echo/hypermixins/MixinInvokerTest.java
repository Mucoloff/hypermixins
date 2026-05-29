package net.echo.hypermixins;

import net.echo.hypermixins.annotations.Invoker;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Overwrite;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies @Invoker trampolines forward to a named target method via INVOKEVIRTUAL.
 */
public class MixinInvokerTest {

    public static class Target {
        public String secret(int n) { return "secret-" + n; }
        public String describe() { return "stub"; }
    }

    @Mixin("net.echo.hypermixins.MixinInvokerTest$Target")
    public static class Mix {
        @Invoker("secret")
        public native String invokeSecret(Object self, int n);

        @Overwrite("describe")
        public String describe(Object self) { return invokeSecret(self, 42) + "!"; }
    }

    @AfterEach
    void clear() { MixinRegistry.clearForTests(); }

    @Test
    void invokerForwardsToTarget() throws Exception {
        Class<?> t = applyMixin(Target.class, Mix.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        assertEquals("secret-42!", t.getMethod("describe").invoke(inst));
    }

    // ---- name-derived target via invokeFoo / callFoo prefix ----

    public static class PrefixTarget {
        public int tally(int x) { return x * 2; }
        public int run() { return 0; }
    }

    @Mixin("net.echo.hypermixins.MixinInvokerTest$PrefixTarget")
    public static class PrefixMix {
        @Invoker
        public native int callTally(Object self, int x);

        @Overwrite("run")
        public int run(Object self) { return callTally(self, 21); }
    }

    // ---- private-target @Invoker ----

    public static class PrivateTarget {
        private int privy() { return 99; }
        public int read() { return 0; }
    }
    @Mixin("net.echo.hypermixins.MixinInvokerTest$PrivateTarget")
    public static class PrivateMix {
        @Invoker("privy")
        public native int callPrivy(Object self);

        @Overwrite("read")
        public int read(Object self) { return callPrivy(self); }
    }

    @Test
    void invokerOnPrivateTarget() throws Exception {
        Class<?> t = applyMixin(PrivateTarget.class, PrivateMix.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        assertEquals(99, t.getMethod("read").invoke(inst));
    }

    @Test
    void invokerNameAutoDerivedFromPrefix() throws Exception {
        Class<?> t = applyMixin(PrefixTarget.class, PrefixMix.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        assertEquals(42, t.getMethod("run").invoke(inst));
    }
}
