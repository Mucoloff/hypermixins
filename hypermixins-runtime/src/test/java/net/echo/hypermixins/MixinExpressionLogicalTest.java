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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MixinExpressionLogicalTest {

    @AfterEach
    void cleanRegistry() { MixinRegistry.clearForTests(); }

    public static volatile int andHits;
    public static volatile int orHits;
    public static volatile int capA, capB, capC, capD;

    public static class LogicalTarget {
        public int andCase(int a, int b, int c, int d) {
            if (a < b && c > d) return 1;
            return 0;
        }

        public int orCase(int a, int b, int c, int d) {
            if (a < b || c > d) return 1;
            return 0;
        }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionLogicalTest$LogicalTarget")
    public static class AndMixin {
        @Expression("? < ? && ? > ?")
        @Inject(method = "andCase", at = @At(point = At.Point.EXPRESSION))
        public void onAnd(Object self) { andHits++; }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionLogicalTest$LogicalTarget")
    public static class OrMixin {
        @Expression("? < ? || ? > ?")
        @Inject(method = "orCase", at = @At(point = At.Point.EXPRESSION))
        public void onOr(Object self) { orHits++; }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionLogicalTest$LogicalTarget")
    public static class AndCaptureMixin {
        @Expression("? < ? && ? > ?")
        @Inject(method = "andCase", at = @At(point = At.Point.EXPRESSION))
        public void onAnd(Object self, int a, int b, int c, int d) {
            capA = a; capB = b; capC = c; capD = d;
        }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionLogicalTest$LogicalTarget")
    public static class NestedMixin {
        @Expression("? < ? && ? > ? && ? == ?")
        @Inject(method = "andCase", at = @At(point = At.Point.EXPRESSION))
        public void onNested(Object self) {}
    }

    @Test
    void andMatchesSharedFalseLabel() throws Exception {
        andHits = 0;
        Class<?> t = applyMixin(LogicalTarget.class, AndMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int out = (int) t.getMethod("andCase", int.class, int.class, int.class, int.class)
            .invoke(inst, 1, 2, 4, 3);
        assertEquals(1, out);
        assertTrue(andHits >= 1);
    }

    @Test
    void orMatchesShortCircuit() throws Exception {
        orHits = 0;
        Class<?> t = applyMixin(LogicalTarget.class, OrMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int out = (int) t.getMethod("orCase", int.class, int.class, int.class, int.class)
            .invoke(inst, 1, 2, 0, 9);
        assertEquals(1, out);
        assertTrue(orHits >= 1);
    }

    @Test
    void andCapturesAllOperands() throws Exception {
        capA = capB = capC = capD = -1;
        Class<?> t = applyMixin(LogicalTarget.class, AndCaptureMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        t.getMethod("andCase", int.class, int.class, int.class, int.class)
            .invoke(inst, 1, 2, 4, 3);
        assertEquals(1, capA);
        assertEquals(2, capB);
        assertEquals(4, capC);
        assertEquals(3, capD);
    }

    @Test
    void nestedLogicalRejected() {
        assertThrows(IllegalStateException.class,
            () -> applyMixin(LogicalTarget.class, NestedMixin.class));
    }
}
