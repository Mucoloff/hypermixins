package net.echo.hypermixins;

import net.echo.hypermixins.annotations.Accessor;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Overwrite;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Accessor-style getter/setter trampolines. The transformer rewrites native mixin methods to
 * read/write the named field on the target via the Object self parameter.
 */
public class MixinAccessorTest {

    public static class Target {
        public int health = 5;
        public String describe() { return "stub"; }
    }

    @Mixin("net.echo.hypermixins.MixinAccessorTest$Target")
    public static class Mix {
        @Accessor("health")
        public native int getHealth(Object self);

        @Accessor("health")
        public native void setHealth(Object self, int v);

        @Overwrite("describe")
        public String describe(Object self) {
            setHealth(self, getHealth(self) + 1);
            return "h=" + getHealth(self);
        }
    }

    @AfterEach
    void clear() { MixinRegistry.clearForTests(); }

    @Test
    void accessorReadWrite() throws Exception {
        Class<?> t = applyMixin(Target.class, Mix.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        // toString → setHealth(getHealth+1); returns "h=6"
        assertEquals("h=6", t.getMethod("describe").invoke(inst));
        assertEquals(6, t.getField("health").getInt(inst));
    }

    // ---- name-derived target field via getFoo / setFoo / isFoo ----

    public static class FlagTarget {
        public boolean active = false;
        public String describe() { return "stub"; }
    }

    @Mixin("net.echo.hypermixins.MixinAccessorTest$FlagTarget")
    public static class FlagMix {
        @Accessor
        public native boolean isActive(Object self);

        @Accessor
        public native void setActive(Object self, boolean v);

        @Overwrite("describe")
        public String describe(Object self) {
            setActive(self, true);
            return Boolean.toString(isActive(self));
        }
    }

    @Test
    void accessorNameAutoDerivedFromPrefix() throws Exception {
        Class<?> t = applyMixin(FlagTarget.class, FlagMix.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        assertEquals("true", t.getMethod("describe").invoke(inst));
        assertTrue(t.getField("active").getBoolean(inst));
    }
}
