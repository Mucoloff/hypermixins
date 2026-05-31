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

public class MixinExpressionArithmeticTest {

    @AfterEach
    void cleanRegistry() {
        MixinRegistry.clearForTests();
    }

    public static volatile int addHits;
    public static volatile int mulHits;

    public static class ArithTarget {
        public int add(int a, int b) {
            return a + b;
        }

        public int product(int a, int b) {
            return a * b;
        }

        public int affine(int a, int b, int c) {
            return a + b * c;
        }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionArithmeticTest$ArithTarget")
    public static class AddMixin {
        @Expression("? + ?")
        @Inject(method = "add", at = @At(point = At.Point.EXPRESSION))
        public void onAdd(Object self) {
            addHits++;
        }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionArithmeticTest$ArithTarget")
    public static class MulMixin {
        @Expression("? * ?")
        @Inject(method = "product", at = @At(point = At.Point.EXPRESSION))
        public void onMul(Object self) {
            mulHits++;
        }
    }

    @Test
    void additionMatchesIaddSite() throws Exception {
        addHits = 0;
        Class<?> t = applyMixin(ArithTarget.class, AddMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int out = (int) t.getMethod("add", int.class, int.class).invoke(inst, 3, 4);
        assertEquals(7, out);
        assertEquals(1, addHits);
    }

    @Test
    void multiplicationMatchesImulSite() throws Exception {
        mulHits = 0;
        Class<?> t = applyMixin(ArithTarget.class, MulMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int out = (int) t.getMethod("product", int.class, int.class).invoke(inst, 5, 6);
        assertEquals(30, out);
        assertEquals(1, mulHits);
    }
}
