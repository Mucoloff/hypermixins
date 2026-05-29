package net.echo.hypermixins;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.ModifyExpressionValue;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @ModifyExpressionValue generalizes @ModifyReturnValue to FIELD and CONSTANT sites — the
 * handler INVOKESTATIC fires after the producing instruction so its input is the pushed value.
 */
public class MixinModifyExpressionValueTest {

    public static class FieldTarget {
        public int health = 5;
        public int read() { return health; }
    }

    @Mixin("net.echo.hypermixins.MixinModifyExpressionValueTest$FieldTarget")
    public static class FieldMix {
        @ModifyExpressionValue(method = "read",
            at = @At(point = At.Point.FIELD,
                desc = "net/echo/hypermixins/MixinModifyExpressionValueTest$FieldTarget.health:I"))
        public static int wrap(int original) { return original * 10; }
    }

    @AfterEach
    void clear() { MixinRegistry.clearForTests(); }

    @Test
    void modifyExpressionValueOnGetField() throws Exception {
        Class<?> t = applyMixin(FieldTarget.class, FieldMix.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        assertEquals(50, t.getMethod("read").invoke(inst));
    }

    public static class ConstTarget {
        public int big() { return 1234567; }
    }
    @Mixin("net.echo.hypermixins.MixinModifyExpressionValueTest$ConstTarget")
    public static class ConstMix {
        @ModifyExpressionValue(method = "big",
            at = @At(point = At.Point.CONSTANT, desc = "I:1234567"))
        public static int swap(int original) { return original + 1; }
    }

    @Test
    void modifyExpressionValueOnConstant() throws Exception {
        Class<?> t = applyMixin(ConstTarget.class, ConstMix.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        assertEquals(1234568, t.getMethod("big").invoke(inst));
    }
}
