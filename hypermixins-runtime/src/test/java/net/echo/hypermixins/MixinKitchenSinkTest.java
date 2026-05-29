package net.echo.hypermixins;

import net.echo.hypermixins.annotations.Accessor;
import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Inject;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.ModifyConstant;
import net.echo.hypermixins.annotations.ModifyReturnValue;
import net.echo.hypermixins.annotations.Original;
import net.echo.hypermixins.annotations.Overwrite;
import net.echo.hypermixins.annotations.Shadow;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Composes a single mixin that mixes @Overwrite + @Original + @Inject + @ModifyReturnValue +
 * @ModifyConstant + @Accessor + @Shadow against one target. Asserts every rewrite path lands
 * on the same target without colliding.
 */
public class MixinKitchenSinkTest {

    public static volatile int injectHits;

    public static class Target {
        public int health = 7;
        public int tag = 42;
        public String compute() {
            int local = 1_000_000; // LDC int (forces matchable constant)
            return Integer.toString(local + Integer.parseInt("3"));
        }
    }

    @Mixin("net.echo.hypermixins.MixinKitchenSinkTest$Target")
    public static class Mix {
        @Shadow
        public int health;

        @Accessor("tag")
        public native int getTag(Object self);

        @Original("compute")
        public native String origCompute(Object self);

        @Overwrite("compute")
        public String compute(Object self) {
            // shadow field read + accessor + original wrap.
            return "h=" + health + ",t=" + getTag(self) + ",base=" + origCompute(self);
        }

        @Inject(method = "compute", at = @At(point = At.Point.HEAD))
        public void onCompute(Object self) { injectHits++; }

        @ModifyConstant(method = "compute",
            constant = @ModifyConstant.Constant(intValue = 1_000_000))
        public static int bumpLdc(int original) { return original + 1; }

        @ModifyReturnValue(method = "compute",
            at = @At(desc = "java/lang/Integer.parseInt(Ljava/lang/String;)I"))
        public static int wrapParse(int original) { return original * 2; }
    }

    @AfterEach
    void clear() { MixinRegistry.clearForTests(); injectHits = 0; }

    @Test
    void everyAnnotationCoexistsOnOneTarget() throws Exception {
        Class<?> t = applyMixin(Target.class, Mix.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        // compute body originally returns "1000000 + 3" = "1000003".
        // @ModifyConstant bumps 1000000 → 1000001.
        // @ModifyReturnValue doubles parseInt("3") = 6.
        // Original therefore yields "1000007".
        // @Overwrite wraps: "h=7,t=42,base=1000007".
        assertEquals("h=7,t=42,base=1000007", t.getMethod("compute").invoke(inst));
        // @Inject HEAD fires once.
        assertEquals(1, injectHits);
    }
}
