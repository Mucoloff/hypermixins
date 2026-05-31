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

public class MixinExpressionInnerCaptureTest {

    @AfterEach
    void cleanRegistry() { MixinRegistry.clearForTests(); }

    public static volatile int innerArg;
    public static volatile String outerArg;

    public static class Inner {
        public Outer pick(int idx) { return new Outer(); }
    }

    public static class Outer {
        public void emit(String msg) {}
    }

    public static class InnerTarget {
        private final Inner inner = new Inner();
        public void run(int idx, String msg) {
            inner.pick(idx).emit(msg);
        }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionInnerCaptureTest$InnerTarget")
    public static class InnerCaptureMixin {
        @Definition(id = "pick",
            method = "net/echo/hypermixins/MixinExpressionInnerCaptureTest$Inner.pick(I)Lnet/echo/hypermixins/MixinExpressionInnerCaptureTest$Outer;")
        @Definition(id = "emit",
            method = "net/echo/hypermixins/MixinExpressionInnerCaptureTest$Outer.emit(Ljava/lang/String;)V")
        @Expression("pick(?).emit(?)")
        @Inject(method = "run", at = @At(point = At.Point.EXPRESSION))
        public void onChainCapture(Object self, int idx, String msg) {
            innerArg = idx;
            outerArg = msg;
        }
    }

    @Test
    void innerWildcardBindsBeforeOuter() throws Exception {
        innerArg = -1;
        outerArg = null;
        Class<?> t = applyMixin(InnerTarget.class, InnerCaptureMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        t.getMethod("run", int.class, String.class).invoke(inst, 5, "hi");
        assertEquals(5, innerArg);
        assertEquals("hi", outerArg);
    }
}
