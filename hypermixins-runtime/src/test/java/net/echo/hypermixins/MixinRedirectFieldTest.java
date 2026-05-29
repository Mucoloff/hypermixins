package net.echo.hypermixins;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Call;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Redirect;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises {@code @Redirect} against field access opcodes (GETFIELD / PUTFIELD / GETSTATIC /
 * PUTSTATIC). Each handler is a static method matching the expected signature for the opcode.
 */
public class MixinRedirectFieldTest {

    public static volatile int writeCount;
    public static volatile int writeValueSeen;

    @AfterEach
    void reset() { MixinRegistry.clearForTests(); writeCount = 0; writeValueSeen = 0; }

    // ---- GETFIELD ----

    public static class GetFieldTarget {
        public int health = 5;
        public int read()  { return health; } // single GETFIELD
    }

    @Mixin("net.echo.hypermixins.MixinRedirectFieldTest$GetFieldTarget")
    public static class GetFieldMixin {
        @Redirect(method = "read", at = @At(
            desc = "net/echo/hypermixins/MixinRedirectFieldTest$GetFieldTarget.health:I",
            call = Call.GETFIELD))
        public static int reroute(Object owner) { return 100; }
    }

    @Test
    void redirectGetField() throws Exception {
        Class<?> t = applyMixin(GetFieldTarget.class, GetFieldMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        assertEquals(100, t.getMethod("read").invoke(inst));
    }

    // ---- PUTFIELD ----

    public static class PutFieldTarget {
        public int health = 0;
        public void set(int v) { health = v; }
    }

    @Mixin("net.echo.hypermixins.MixinRedirectFieldTest$PutFieldTarget")
    public static class PutFieldMixin {
        @Redirect(method = "set", at = @At(
            desc = "net/echo/hypermixins/MixinRedirectFieldTest$PutFieldTarget.health:I",
            call = Call.PUTFIELD))
        public static void reroute(Object owner, int v) {
            writeCount++;
            writeValueSeen = v;
        }
    }

    @Test
    void redirectPutField() throws Exception {
        Class<?> t = applyMixin(PutFieldTarget.class, PutFieldMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        t.getMethod("set", int.class).invoke(inst, 42);
        assertEquals(1, writeCount);
        assertEquals(42, writeValueSeen);
        // Original field stayed at default because PUTFIELD was redirected away.
        assertEquals(0, t.getField("health").getInt(inst));
    }

    // ---- GETSTATIC ----

    public static class GetStaticTarget {
        public static int total = 7;
        public int read() { return total; }
    }

    @Mixin("net.echo.hypermixins.MixinRedirectFieldTest$GetStaticTarget")
    public static class GetStaticMixin {
        @Redirect(method = "read", at = @At(
            desc = "net/echo/hypermixins/MixinRedirectFieldTest$GetStaticTarget.total:I",
            call = Call.GETSTATIC))
        public static int reroute() { return 999; }
    }

    @Test
    void redirectGetStatic() throws Exception {
        Class<?> t = applyMixin(GetStaticTarget.class, GetStaticMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        assertEquals(999, t.getMethod("read").invoke(inst));
    }

    // ---- PUTSTATIC ----

    public static class PutStaticTarget {
        public static int log = 0;
        public void bump(int v) { log = v; }
    }

    @Mixin("net.echo.hypermixins.MixinRedirectFieldTest$PutStaticTarget")
    public static class PutStaticMixin {
        @Redirect(method = "bump", at = @At(
            desc = "net/echo/hypermixins/MixinRedirectFieldTest$PutStaticTarget.log:I",
            call = Call.PUTSTATIC))
        public static void reroute(int v) {
            writeCount++;
            writeValueSeen = v;
        }
    }

    @Test
    void redirectPutStatic() throws Exception {
        Class<?> t = applyMixin(PutStaticTarget.class, PutStaticMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        t.getMethod("bump", int.class).invoke(inst, 17);
        assertEquals(1, writeCount);
        assertEquals(17, writeValueSeen);
        // Original static field untouched by the redirected store.
        assertEquals(0, t.getField("log").getInt(null));
    }
}
