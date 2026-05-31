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

public class MixinExpressionNotTest {

    @AfterEach
    void cleanRegistry() { MixinRegistry.clearForTests(); }

    public static volatile int hits;

    public static class NotTarget {
        // `a >= b` compiles to a forward IF_ICMPLT (negated). !(? < ?) folds to >= → IF_ICMPLT.
        public int branchGe(int a, int b) {
            if (a >= b) return 1;
            return 0;
        }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionNotTest$NotTarget")
    public static class NotMixin {
        @Expression("!(? < ?)")
        @Inject(method = "branchGe", at = @At(point = At.Point.EXPRESSION))
        public void onGe(Object self) { hits++; }
    }

    @Test
    void notLessThanMatchesGreaterEqualSite() throws Exception {
        hits = 0;
        Class<?> t = applyMixin(NotTarget.class, NotMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int out = (int) t.getMethod("branchGe", int.class, int.class).invoke(inst, 5, 3);
        assertEquals(1, out);
        assertTrue(hits >= 1);
    }
}
