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

public class MixinExpressionLiteralTest {

    @AfterEach
    void cleanRegistry() {
        MixinRegistry.clearForTests();
    }

    public static volatile int intHits;
    public static volatile int strHits;
    public static volatile int boolHits;
    public static volatile int nullHits;

    public static class Sink {
        public void emitInt(int v) {}
        public void emitStr(String s) {}
        public void emitBool(boolean b) {}
        public void emitObj(Object o) {}
    }

    public static class LiteralTarget {
        private final Sink sink = new Sink();
        public void run() {
            sink.emitInt(42);
            sink.emitInt(7);
            sink.emitStr("hello");
            sink.emitStr("world");
            sink.emitBool(true);
            sink.emitBool(false);
            sink.emitObj(null);
            sink.emitObj("not null");
        }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionLiteralTest$LiteralTarget")
    public static class IntLiteralMixin {
        @Definition(id = "emitInt",
            method = "net/echo/hypermixins/MixinExpressionLiteralTest$Sink.emitInt(I)V")
        @Expression("emitInt(42)")
        @Inject(method = "run", at = @At(point = At.Point.EXPRESSION))
        public void onEmit42(Object self) { intHits++; }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionLiteralTest$LiteralTarget")
    public static class StringLiteralMixin {
        @Definition(id = "emitStr",
            method = "net/echo/hypermixins/MixinExpressionLiteralTest$Sink.emitStr(Ljava/lang/String;)V")
        @Expression("emitStr(\"hello\")")
        @Inject(method = "run", at = @At(point = At.Point.EXPRESSION))
        public void onHello(Object self) { strHits++; }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionLiteralTest$LiteralTarget")
    public static class BoolLiteralMixin {
        @Definition(id = "emitBool",
            method = "net/echo/hypermixins/MixinExpressionLiteralTest$Sink.emitBool(Z)V")
        @Expression("emitBool(true)")
        @Inject(method = "run", at = @At(point = At.Point.EXPRESSION))
        public void onTrue(Object self) { boolHits++; }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionLiteralTest$LiteralTarget")
    public static class NullLiteralMixin {
        @Definition(id = "emitObj",
            method = "net/echo/hypermixins/MixinExpressionLiteralTest$Sink.emitObj(Ljava/lang/Object;)V")
        @Expression("emitObj(null)")
        @Inject(method = "run", at = @At(point = At.Point.EXPRESSION))
        public void onNull(Object self) { nullHits++; }
    }

    @Test
    void intLiteralMatchesExactValue() throws Exception {
        intHits = 0;
        Class<?> t = applyMixin(LiteralTarget.class, IntLiteralMixin.class);
        t.getMethod("run").invoke(t.getDeclaredConstructor().newInstance());
        assertEquals(1, intHits);
    }

    @Test
    void stringLiteralMatchesExactValue() throws Exception {
        strHits = 0;
        Class<?> t = applyMixin(LiteralTarget.class, StringLiteralMixin.class);
        t.getMethod("run").invoke(t.getDeclaredConstructor().newInstance());
        assertEquals(1, strHits);
    }

    @Test
    void boolLiteralMatchesExactValue() throws Exception {
        boolHits = 0;
        Class<?> t = applyMixin(LiteralTarget.class, BoolLiteralMixin.class);
        t.getMethod("run").invoke(t.getDeclaredConstructor().newInstance());
        assertEquals(1, boolHits);
    }

    @Test
    void nullLiteralMatchesAconstNull() throws Exception {
        nullHits = 0;
        Class<?> t = applyMixin(LiteralTarget.class, NullLiteralMixin.class);
        t.getMethod("run").invoke(t.getDeclaredConstructor().newInstance());
        assertEquals(1, nullHits);
    }
}
