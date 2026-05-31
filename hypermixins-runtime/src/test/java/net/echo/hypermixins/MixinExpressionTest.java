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

public class MixinExpressionTest {

    @AfterEach
    void cleanRegistry() {
        MixinRegistry.clearForTests();
    }

    public static volatile int callHits;
    public static volatile int fieldHits;

    public static class PrintHelper {
        public void greet(String msg) {}
    }

    public static class ExpressionTarget {
        public int counter = 0;
        private final PrintHelper helper = new PrintHelper();

        public void run() {
            helper.greet("hello");
            counter = counter + 1;
        }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionTest$ExpressionTarget")
    public static class CallMixin {
        @Definition(id = "greet",
            method = "net/echo/hypermixins/MixinExpressionTest$PrintHelper.greet(Ljava/lang/String;)V")
        @Expression("greet(?)")
        @Inject(method = "run", at = @At(point = At.Point.EXPRESSION))
        public void onGreet(Object self) {
            callHits++;
        }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionTest$ExpressionTarget")
    public static class FieldMixin {
        @Definition(id = "counter",
            field = "net/echo/hypermixins/MixinExpressionTest$ExpressionTarget.counter:I")
        @Expression("counter")
        @Inject(method = "run", at = @At(point = At.Point.EXPRESSION))
        public void onCounter(Object self) {
            fieldHits++;
        }
    }

    @Test
    void callExpressionMatchesInvokeSite() throws Exception {
        callHits = 0;
        Class<?> t = applyMixin(ExpressionTarget.class, CallMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        t.getMethod("run").invoke(inst);
        assertEquals(1, callHits);
    }

    @Test
    void fieldExpressionMatchesGetfieldAndPutfield() throws Exception {
        fieldHits = 0;
        Class<?> t = applyMixin(ExpressionTarget.class, FieldMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        t.getMethod("run").invoke(inst);
        // run() does: GETFIELD counter, ICONST_1, IADD, PUTFIELD counter — both GETFIELD and
        // PUTFIELD match the @Expression "counter" definition.
        assertEquals(2, fieldHits);
    }
}
