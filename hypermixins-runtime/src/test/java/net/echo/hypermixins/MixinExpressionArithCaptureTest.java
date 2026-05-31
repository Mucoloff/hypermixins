package net.echo.hypermixins;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Expression;
import net.echo.hypermixins.annotations.Inject;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MixinExpressionArithCaptureTest {

    @AfterEach
    void cleanRegistry() { MixinRegistry.clearForTests(); }

    public static volatile int lhs;
    public static volatile int rhs;

    public static class ArithCaptureTarget {
        public int add(int a, int b) {
            return a + b;
        }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionArithCaptureTest$ArithCaptureTarget")
    public static class AddCaptureMixin {
        @Expression("? + ?")
        @Inject(method = "add", at = @At(point = At.Point.EXPRESSION))
        public void onAdd(Object self, int a, int b) {
            lhs = a;
            rhs = b;
        }
    }

    @Test
    void arithOperandsBindToHandlerParams() throws Exception {
        lhs = -1;
        rhs = -1;
        Class<?> t = applyMixin(ArithCaptureTarget.class, AddCaptureMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int out = (int) t.getMethod("add", int.class, int.class).invoke(inst, 4, 9);
        assertEquals(13, out);
        assertEquals(4, lhs);
        assertEquals(9, rhs);
    }
}
