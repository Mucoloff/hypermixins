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
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MixinExpressionCaptureTest {

    @AfterEach
    void cleanRegistry() {
        MixinRegistry.clearForTests();
    }

    public static volatile String captured;
    public static volatile int newCounter;
    public static volatile int fieldHits;

    public static class Sink {
        public void accept(String s) {}
    }

    public static class CaptureTarget {
        public int counter = 0;
        private final Sink sink = new Sink();

        public void emit(String msg) {
            sink.accept(msg);
        }

        public void set(int x) {
            this.counter = x;
        }

        public int read() {
            return this.counter;
        }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionCaptureTest$CaptureTarget")
    public static class CallCaptureMixin {
        @Definition(id = "accept",
            method = "net/echo/hypermixins/MixinExpressionCaptureTest$Sink.accept(Ljava/lang/String;)V")
        @Expression("accept(?)")
        @Inject(method = "emit", at = @At(point = At.Point.EXPRESSION))
        public void onAccept(Object self, String s) {
            captured = s;
        }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionCaptureTest$CaptureTarget")
    public static class AssignCaptureMixin {
        @Definition(id = "counter",
            field = "net/echo/hypermixins/MixinExpressionCaptureTest$CaptureTarget.counter:I")
        @Expression("this.counter = ?")
        @Inject(method = "set", at = @At(point = At.Point.EXPRESSION))
        public void onAssign(Object self, int newVal) {
            newCounter = newVal;
        }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionCaptureTest$CaptureTarget")
    public static class ThisFieldReadMixin {
        @Definition(id = "counter",
            field = "net/echo/hypermixins/MixinExpressionCaptureTest$CaptureTarget.counter:I")
        @Expression("this.counter")
        @Inject(method = "read", at = @At(point = At.Point.EXPRESSION))
        public void onCounterAccess(Object self) {
            fieldHits++;
        }
    }

    @Test
    void wildcardArgBindsToLoadedLocal() throws Exception {
        captured = null;
        Class<?> t = applyMixin(CaptureTarget.class, CallCaptureMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        t.getMethod("emit", String.class).invoke(inst, "hello");
        assertEquals("hello", captured);
    }

    @Test
    void thisAssignmentBindsRhsLocal() throws Exception {
        newCounter = -1;
        Class<?> t = applyMixin(CaptureTarget.class, AssignCaptureMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        t.getMethod("set", int.class).invoke(inst, 42);
        assertEquals(42, newCounter);
    }

    @Test
    void thisFieldReadMatchesGetfield() throws Exception {
        fieldHits = 0;
        Class<?> t = applyMixin(CaptureTarget.class, ThisFieldReadMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int v = (int) t.getMethod("read").invoke(inst);
        assertEquals(0, v);
        assertTrue(fieldHits >= 1);
    }
}
