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
    public static volatile int and3Hits, or3Hits, mixedHits, mixedLeftHits;
    public static volatile int n0, n1, n2, n3, n4, n5;

    public static class LogicalTarget {
        public int andCase(int a, int b, int c, int d) {
            if (a < b && c > d) return 1;
            return 0;
        }

        public int orCase(int a, int b, int c, int d) {
            if (a < b || c > d) return 1;
            return 0;
        }

        public int and3(int a, int b, int c, int d, int e, int f) {
            if (a < b && c < d && e < f) return 1;
            return 0;
        }

        public int or3(int a, int b, int c, int d, int e, int f) {
            if (a < b || c < d || e < f) return 1;
            return 0;
        }

        public int mixed(int a, int b, int c, int d, int e, int f) {
            if (a < b && (c < d || e < f)) return 1;
            return 0;
        }

        public int mixedLeft(int a, int b, int c, int d, int e, int f) {
            if ((a < b || c < d) && e < f) return 1;
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
    public static class And3Mixin {
        @Expression("? < ? && ? < ? && ? < ?")
        @Inject(method = "and3", at = @At(point = At.Point.EXPRESSION))
        public void onAnd3(Object self) { and3Hits++; }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionLogicalTest$LogicalTarget")
    public static class Or3Mixin {
        @Expression("? < ? || ? < ? || ? < ?")
        @Inject(method = "or3", at = @At(point = At.Point.EXPRESSION))
        public void onOr3(Object self) { or3Hits++; }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionLogicalTest$LogicalTarget")
    public static class NaryCaptureMixin {
        @Expression("? < ? && ? < ? && ? < ?")
        @Inject(method = "and3", at = @At(point = At.Point.EXPRESSION))
        public void onAnd3(Object self, int p0, int p1, int p2, int p3, int p4, int p5) {
            n0 = p0; n1 = p1; n2 = p2; n3 = p3; n4 = p4; n5 = p5;
        }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionLogicalTest$LogicalTarget")
    public static class MixedRightMixin {
        @Expression("? < ? && (? < ? || ? < ?)")
        @Inject(method = "mixed", at = @At(point = At.Point.EXPRESSION))
        public void onMixed(Object self) { mixedHits++; }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionLogicalTest$LogicalTarget")
    public static class MixedLeftMixin {
        @Expression("(? < ? || ? < ?) && ? < ?")
        @Inject(method = "mixedLeft", at = @At(point = At.Point.EXPRESSION))
        public void onMixedLeft(Object self) { mixedLeftHits++; }
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
    void and3MatchesSharedLabel() throws Exception {
        and3Hits = 0;
        Class<?> t = applyMixin(LogicalTarget.class, And3Mixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int out = (int) t.getMethod("and3", int.class, int.class, int.class, int.class, int.class, int.class)
            .invoke(inst, 1, 2, 3, 4, 5, 6);
        assertEquals(1, out);
        assertTrue(and3Hits >= 1);
    }

    @Test
    void or3MatchesShortCircuit() throws Exception {
        or3Hits = 0;
        Class<?> t = applyMixin(LogicalTarget.class, Or3Mixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int out = (int) t.getMethod("or3", int.class, int.class, int.class, int.class, int.class, int.class)
            .invoke(inst, 1, 2, 9, 0, 9, 0);
        assertEquals(1, out);
        assertTrue(or3Hits >= 1);
    }

    @Test
    void naryCapturesAllOperands() throws Exception {
        n0 = n1 = n2 = n3 = n4 = n5 = -1;
        Class<?> t = applyMixin(LogicalTarget.class, NaryCaptureMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        t.getMethod("and3", int.class, int.class, int.class, int.class, int.class, int.class)
            .invoke(inst, 1, 2, 3, 4, 5, 6);
        assertEquals(1, n0);
        assertEquals(2, n1);
        assertEquals(3, n2);
        assertEquals(4, n3);
        assertEquals(5, n4);
        assertEquals(6, n5);
    }

    @Test
    void mixedRightNestedMatches() throws Exception {
        // `a && (b || c)` — 2-label, recognised by the recursive matcher.
        mixedHits = 0;
        Class<?> t = applyMixin(LogicalTarget.class, MixedRightMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int out = (int) t.getMethod("mixed", int.class, int.class, int.class, int.class, int.class, int.class)
            .invoke(inst, 1, 2, 9, 0, 3, 4);
        assertEquals(1, out);
        assertTrue(mixedHits >= 1);
    }

    @Test
    void mixedLeftNestedMatches() throws Exception {
        // `(a || b) && c` — needs an intermediate label; the recursive matcher resolves it.
        mixedLeftHits = 0;
        Class<?> t = applyMixin(LogicalTarget.class, MixedLeftMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int out = (int) t.getMethod("mixedLeft", int.class, int.class, int.class, int.class, int.class, int.class)
            .invoke(inst, 1, 2, 9, 0, 3, 4);
        assertEquals(1, out);
        assertTrue(mixedLeftHits >= 1);
    }
}
