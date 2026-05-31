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
    public static volatile int loopHits;

    public static class CompTarget {
        public int branchEq(int a, int b) {
            if (a == b) return 1;
            return 0;
        }

        public int branchLt(int a, int b) {
            if (a < b) return 1;
            return 0;
        }

        // Bottom-test loop: `a < limit` compiles to a BACKWARD IF_ICMPLT to the body label.
        public int countUp(int a, int limit) {
            int n = 0;
            while (a < limit) { n++; a++; }
            return n;
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

    @Mixin("net.echo.hypermixins.MixinExpressionComparisonTest$CompTarget")
    public static class NotEqMixin {
        @Expression("? != ?")
        @Inject(method = "branchEq", at = @At(point = At.Point.EXPRESSION))
        public void onNotEq(Object self) {}
    }

    @Mixin("net.echo.hypermixins.MixinExpressionComparisonTest$CompTarget")
    public static class LoopLtMixin {
        @Expression("? < ?")
        @Inject(method = "countUp", at = @At(point = At.Point.EXPRESSION))
        public void onLoop(Object self) { loopHits++; }
    }

    @Test
    void ltMatchesLoopBottomTest() throws Exception {
        loopHits = 0;
        Class<?> t = applyMixin(CompTarget.class, LoopLtMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int out = (int) t.getMethod("countUp", int.class, int.class).invoke(inst, 0, 3);
        assertEquals(3, out);
        // The loop's `a < limit` compiles to a backward IF_ICMPLT — the if-only mapping would
        // have missed it. Branch-direction resolution makes `? < ?` match here.
        assertTrue(loopHits >= 1);
    }

    @Test
    void notEqDoesNotMatchEqSite() {
        // branchEq's `if (a == b)` compiles to IF_ICMPNE. Under branch-sense, `!=` maps to
        // IF_ICMPEQ only, so it must NOT match the == site — proving == / != are distinct.
        // No match → InjectPass throws "found no matching site".
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> applyMixin(CompTarget.class, NotEqMixin.class));
    }
}
