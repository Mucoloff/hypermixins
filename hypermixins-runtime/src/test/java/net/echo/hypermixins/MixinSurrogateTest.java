package net.echo.hypermixins;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Inject;
import net.echo.hypermixins.annotations.Local;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Surrogate;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MixinSurrogateTest {

    @AfterEach
    void cleanRegistry() {
        MixinRegistry.clearForTests();
    }

    public static volatile String tag;

    public static class SurrogateTarget {
        public int compute(int x) {
            int local = x * 2;
            return local;
        }
    }

    @Mixin("net.echo.hypermixins.MixinSurrogateTest$SurrogateTarget")
    public static class SurrogateMixin {
        // Primary signature mismatches — @Local of type String not live at HEAD → throws
        // InjectSignatureMismatch and the runtime falls back to the @Surrogate handler below.
        @Inject(method = "compute", at = @At(point = At.Point.HEAD))
        public void onComputePrimary(Object self, int x, @Local String missing) {
            tag = "primary:" + missing;
        }

        @Surrogate
        @Inject(method = "compute", at = @At(point = At.Point.HEAD))
        public void onComputeSurrogate(Object self, int x) {
            tag = "surrogate:" + x;
        }
    }

    @Test
    void surrogateRunsWhenPrimarySignatureMismatches() throws Exception {
        tag = null;
        Class<?> t = applyMixin(SurrogateTarget.class, SurrogateMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int out = (int) t.getMethod("compute", int.class).invoke(inst, 5);
        assertEquals(10, out);
        assertEquals("surrogate:5", tag);
    }
}
