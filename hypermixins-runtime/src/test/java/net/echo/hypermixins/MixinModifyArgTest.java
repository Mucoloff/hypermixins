package net.echo.hypermixins;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.ModifyArg;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * v1 @ModifyArg supports the last argument of an INVOKE only. Confirmed by intercepting
 * the {@code Integer.valueOf(int)} call's argument inside a target method.
 */
public class MixinModifyArgTest {

    public static class Target {
        public String render(int x) {
            return Integer.valueOf(x).toString();
        }
    }

    @Mixin("net.echo.hypermixins.MixinModifyArgTest$Target")
    public static class Mix {
        @ModifyArg(method = "render", at = @At(desc = "java/lang/Integer.valueOf(I)Ljava/lang/Integer;"), index = 0)
        public static int swap(int original) { return original * 10; }
    }

    @AfterEach
    void clear() { MixinRegistry.clearForTests(); }

    @Test
    void modifyLastArgOfInvoke() throws Exception {
        Class<?> t = applyMixin(Target.class, Mix.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        // input 4 → handler multiplies by 10 → Integer.valueOf(40) → "40"
        assertEquals("40", t.getMethod("render", int.class).invoke(inst, 4));
    }
}
