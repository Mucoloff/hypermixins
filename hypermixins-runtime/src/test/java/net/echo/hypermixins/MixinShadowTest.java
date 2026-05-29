package net.echo.hypermixins;

import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Overwrite;
import net.echo.hypermixins.annotations.Shadow;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises {@code @Shadow} method forwarding: the mixin declares a {@code native} shim that
 * the transformer rewrites into a {@code CHECKCAST + INVOKEVIRTUAL} on the target. The shim is
 * then called from an {@code @Overwrite} handler to reach a sibling target method.
 */
public class MixinShadowTest {

    public static class Target {
        public String greet(String name) { return "hi-" + name; }
        public String tag()               { return "tagged"; }
    }

    @Mixin("net.echo.hypermixins.MixinShadowTest$Target")
    public static class TagShadowingMixin {
        @Shadow("tag")
        public native String tag(Object self);

        @Overwrite("greet")
        public String greet(Object self, String name) { return tag(self) + "/" + name; }
    }

    @AfterEach
    void reset() { MixinRegistry.clearForTests(); }

    @Test
    void shadowForwardsToTargetMethod() throws Exception {
        Class<?> t = applyMixin(Target.class, TagShadowingMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        String result = (String) t.getMethod("greet", String.class).invoke(inst, "alice");
        assertEquals("tagged/alice", result);
    }

    // ---- field-level @Shadow ----

    public static class FieldTarget {
        public int health = 10;
        public int read() { return health; }
        public void write(int v) { health = v; }
    }

    @Mixin("net.echo.hypermixins.MixinShadowTest$FieldTarget")
    public static class FieldShadowMixin {
        @net.echo.hypermixins.annotations.Shadow
        public int health;

        @net.echo.hypermixins.annotations.Overwrite("read")
        public int read(Object self) { return health + 1; }

        @net.echo.hypermixins.annotations.Overwrite("write")
        public void write(Object self, int v) { health = v * 2; }
    }

    // ---- @Shadow(prefix = ...) ----

    public static class PrefixTarget {
        public String tagged() { return "tag"; }
        public String greet() { return "greet"; }
    }
    @Mixin("net.echo.hypermixins.MixinShadowTest$PrefixTarget")
    public static class PrefixShadowMixin {
        @net.echo.hypermixins.annotations.Shadow(prefix = "shadow$")
        public native String shadow$tagged(Object self);

        @net.echo.hypermixins.annotations.Overwrite("greet")
        public String greet(Object self) { return shadow$tagged(self) + "!"; }
    }

    @org.junit.jupiter.api.Test
    void shadowMethodPrefixStripped() throws Exception {
        Class<?> t = applyMixin(PrefixTarget.class, PrefixShadowMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        assertEquals("tag!", t.getMethod("greet").invoke(inst));
    }

    // ---- static @Shadow field ----

    public static class StaticFieldTarget {
        public static int counter = 0;
        public int snapshot() { return counter; }
        public void bump(int v) { counter = v; }
    }

    @Mixin("net.echo.hypermixins.MixinShadowTest$StaticFieldTarget")
    public static class StaticFieldShadowMixin {
        @net.echo.hypermixins.annotations.Shadow
        public static int counter;

        @net.echo.hypermixins.annotations.Overwrite("snapshot")
        public int snapshot(Object self) { return counter * 10; }

        @net.echo.hypermixins.annotations.Overwrite("bump")
        public void bump(Object self, int v) { counter = v + 1; }
    }

    @Test
    void shadowStaticFieldReadAndWrite() throws Exception {
        Class<?> t = applyMixin(StaticFieldTarget.class, StaticFieldShadowMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        // Reset target static field via reflection.
        t.getField("counter").setInt(null, 0);
        t.getMethod("bump", int.class).invoke(inst, 4);
        assertEquals(5, t.getField("counter").getInt(null));
        assertEquals(50, t.getMethod("snapshot").invoke(inst));
    }

    @Test
    void shadowFieldReadAndWrite() throws Exception {
        Class<?> t = applyMixin(FieldTarget.class, FieldShadowMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        // read() returns the target's field + 1
        assertEquals(11, t.getMethod("read").invoke(inst));
        // write(5) stores 10 on the target's field
        t.getMethod("write", int.class).invoke(inst, 5);
        assertEquals(10, t.getField("health").getInt(inst));
        assertEquals(11, t.getMethod("read").invoke(inst));
        // sanity: read sees updated value
        t.getMethod("write", int.class).invoke(inst, 50);
        assertEquals(100, t.getField("health").getInt(inst));
    }
}
