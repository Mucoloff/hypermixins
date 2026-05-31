package net.echo.hypermixins;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Definition;
import net.echo.hypermixins.annotations.Expression;
import net.echo.hypermixins.annotations.Inject;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MixinExpressionNamedCaptureTest {

    @AfterEach
    void cleanRegistry() {
        MixinRegistry.clearForTests();
    }

    public static volatile int capturedHi;
    public static volatile String capturedMsg;

    public static class Writer {
        public void write(int hi, String msg) {}
    }

    public static class NamedTarget {
        private final Writer writer = new Writer();

        public void run(int x, String s) {
            writer.write(x, s);
        }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionNamedCaptureTest$NamedTarget")
    public static class NamedMixin {
        @Definition(id = "write",
            method = "net/echo/hypermixins/MixinExpressionNamedCaptureTest$Writer.write(ILjava/lang/String;)V")
        // Names swap deliberately vs declaration order to prove name-based binding.
        @Expression("write(hi, msg)")
        @Inject(method = "run", at = @At(point = At.Point.EXPRESSION))
        public void onWrite(Object self, String msg, int hi) {
            capturedHi = hi;
            capturedMsg = msg;
        }
    }

    @Test
    void namedCapturesBindByParamName() throws Exception {
        capturedHi = -1;
        capturedMsg = null;
        Class<?> t = applyMixin(NamedTarget.class, NamedMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        t.getMethod("run", int.class, String.class).invoke(inst, 7, "hello");
        assertEquals(7, capturedHi);
        assertEquals("hello", capturedMsg);
    }
}
