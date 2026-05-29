package net.echo.hypermixins;

import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Overwrite;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Static target methods are now overwriteable. The transformer adds a static field on the
 * target holding the singleton mixin instance, initializes it in &lt;clinit&gt;, and emits
 * static {@code __original$} / {@code __dispatch$} synthetics so the INVOKEDYNAMIC call site
 * resolves via {@code findStatic}.
 */
public class MixinStaticTargetTest {

    public static class StaticTarget {
        public static int factor(int x) { return x * 2; }
    }

    @Mixin("net.echo.hypermixins.MixinStaticTargetTest$StaticTarget")
    public static class StaticOverwriteMixin {
        @Overwrite("factor")
        public int factor(Object self, int x) { return x * 3; }
    }

    @AfterEach
    void clear() { MixinRegistry.clearForTests(); }

    @Test
    void staticTargetOverwriteRoutesThroughMixin() throws Exception {
        Class<?> t = applyMixin(StaticTarget.class, StaticOverwriteMixin.class);
        int result = (int) t.getMethod("factor", int.class).invoke(null, 7);
        assertEquals(21, result);
    }

    // ---- @Original trampoline back to a static target ----

    public static class StaticOrigTarget {
        public static int doubled(int x) { return x * 2; }
    }

    @Mixin("net.echo.hypermixins.MixinStaticTargetTest$StaticOrigTarget")
    public static class StaticOriginalMixin {
        @net.echo.hypermixins.annotations.Original("doubled")
        public native int origDoubled(Object self, int x);

        @Overwrite("doubled")
        public int doubled(Object self, int x) {
            // origDoubled forwards to the static __original$ synthetic on the target.
            return origDoubled(self, x) + 1;
        }
    }

    @Test
    void originalTrampolineOnStaticTarget() throws Exception {
        Class<?> t = applyMixin(StaticOrigTarget.class, StaticOriginalMixin.class);
        int result = (int) t.getMethod("doubled", int.class).invoke(null, 5);
        // original returns 10, mixin adds 1 → 11
        assertEquals(11, result);
    }
}
