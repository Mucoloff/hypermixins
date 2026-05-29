package net.echo.hypermixins;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.ModifyReceiver;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Replaces the receiver of a virtual call site. Handler T(T) takes the original receiver and
 * returns the replacement; the transformer captures the existing args into temp locals so the
 * handler operates on a bare receiver on top of the stack, then restores the args.
 */
public class MixinModifyReceiverTest {

    public static class Target {
        public int run() {
            return "world".length();
        }
    }

    @Mixin("net.echo.hypermixins.MixinModifyReceiverTest$Target")
    public static class Mix {
        @ModifyReceiver(method = "run",
            at = @At(desc = "java/lang/String.length()I"))
        public static String swap(String original) { return original + "!!"; }
    }

    @AfterEach
    void clear() { MixinRegistry.clearForTests(); }

    @Test
    void modifyReceiverReplacesCallTarget() throws Exception {
        Class<?> t = applyMixin(Target.class, Mix.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        // length called on "world" + "!!" = 7
        assertEquals(7, t.getMethod("run").invoke(inst));
    }
}
