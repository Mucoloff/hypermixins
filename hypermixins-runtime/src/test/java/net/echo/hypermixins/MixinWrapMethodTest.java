package net.echo.hypermixins;

import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Operation;
import net.echo.hypermixins.annotations.WrapMethod;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MixinWrapMethodTest {

    @AfterEach
    void cleanRegistry() {
        MixinRegistry.clearForTests();
    }

    public static class WmTarget {
        public int compute(int x) {
            return x + 1;
        }
    }

    @Mixin("net.echo.hypermixins.MixinWrapMethodTest$WmTarget")
    public static class WmMixin {
        @WrapMethod("compute")
        public int wrap(Object self, int x, Operation<Integer> op) throws Throwable {
            int raw = (Integer) op.call(self, x);
            return raw * 10;
        }
    }

    @Test
    void wrapMethodPostprocessesResult() throws Exception {
        Class<?> t = applyMixin(WmTarget.class, WmMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int result = (int) t.getMethod("compute", int.class).invoke(inst, 3);
        // op.call(self, 3) → 4; handler multiplies → 40.
        assertEquals(40, result);
    }
}
