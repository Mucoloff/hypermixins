package net.echo.hypermixins;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.ModifyReturnValue;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Intercepts the return value of a call site inside the target method. The transformer inserts
 * an {@code INVOKESTATIC} to the handler immediately after the matched INVOKE, leaving the
 * original call's stack value as the handler's input and pushing the handler's result back.
 */
public class MixinModifyReturnValueTest {

    public static class Target {
        public int run(String tag) {
            return Integer.parseInt(tag) + 1;
        }
    }

    @Mixin("net.echo.hypermixins.MixinModifyReturnValueTest$Target")
    public static class Mix {
        @ModifyReturnValue(method = "run",
            at = @At(desc = "java/lang/Integer.parseInt(Ljava/lang/String;)I"))
        public static int wrap(int original) { return original * 10; }
    }

    @AfterEach
    void clear() { MixinRegistry.clearForTests(); }

    @Test
    void modifyReturnValueWrapsInvokeResult() throws Exception {
        Class<?> t = applyMixin(Target.class, Mix.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        // parseInt("4") = 4 → wrap returns 40 → run returns 41
        assertEquals(41, t.getMethod("run", String.class).invoke(inst, "4"));
    }

    // ---- wildcard @At#desc ----

    public static class WildcardTarget {
        public int run() { return Integer.parseInt("3"); }
    }
    @Mixin("net.echo.hypermixins.MixinModifyReturnValueTest$WildcardTarget")
    public static class WildcardMix {
        @ModifyReturnValue(method = "run", at = @At(desc = "java/lang/Integer.parseInt*"))
        public static int doubleIt(int original) { return original * 2; }
    }

    @Test
    void modifyReturnValueWildcard() throws Exception {
        Class<?> t = applyMixin(WildcardTarget.class, WildcardMix.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        // parseInt("3") = 3 → wildcard matches → 6
        assertEquals(6, t.getMethod("run").invoke(inst));
    }
}
