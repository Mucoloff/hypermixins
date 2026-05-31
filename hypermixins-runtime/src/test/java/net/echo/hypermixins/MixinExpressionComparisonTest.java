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
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MixinExpressionComparisonTest {

    @AfterEach
    void cleanRegistry() {
        MixinRegistry.clearForTests();
    }

    public static volatile int eqHits;
    public static volatile int ltHits;

    public static class CompTarget {
        public int branchEq(int a, int b) {
            if (a == b) return 1;
            return 0;
        }

        public int branchLt(int a, int b) {
            if (a < b) return 1;
            return 0;
        }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionComparisonTest$CompTarget")
    public static class EqMixin {
        @Expression("? == ?")
        @Inject(method = "branchEq", at = @At(point = At.Point.EXPRESSION))
        public void onEq(Object self) { eqHits++; }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionComparisonTest$CompTarget")
    public static class LtMixin {
        @Expression("? < ?")
        @Inject(method = "branchLt", at = @At(point = At.Point.EXPRESSION))
        public void onLt(Object self) { ltHits++; }
    }

    @Test
    void eqMatchesIfIcmpeq() throws Exception {
        eqHits = 0;
        Class<?> t = applyMixin(CompTarget.class, EqMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int out = (int) t.getMethod("branchEq", int.class, int.class).invoke(inst, 5, 5);
        assertEquals(1, out);
        assertTrue(eqHits >= 1);
    }

    @Test
    void ltMatchesIfIcmplt() throws Exception {
        ltHits = 0;
        Class<?> t = applyMixin(CompTarget.class, LtMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int out = (int) t.getMethod("branchLt", int.class, int.class).invoke(inst, 3, 5);
        assertEquals(1, out);
        assertTrue(ltHits >= 1);
    }
}
