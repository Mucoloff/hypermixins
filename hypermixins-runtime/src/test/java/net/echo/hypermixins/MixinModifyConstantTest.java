package net.echo.hypermixins;

import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.ModifyConstant;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @ModifyConstant inserts a static handler call immediately after a matching constant load
 * (LDC / BIPUSH / SIPUSH / ICONST_n). The handler receives the original constant and returns
 * the replacement, leaving the new value on stack for the rest of the target body.
 */
public class MixinModifyConstantTest {

    public static class IntTarget {
        public int compute() { return 999999; } // LDC-forced int constant
    }

    @Mixin("net.echo.hypermixins.MixinModifyConstantTest$IntTarget")
    public static class IntMix {
        @ModifyConstant(method = "compute", constant = @ModifyConstant.Constant(intValue = 999999))
        public static int swap(int original) { return original * 2; }
    }

    @AfterEach
    void clear() { MixinRegistry.clearForTests(); }

    @Test
    void modifyConstantIntReplacesLdc() throws Exception {
        Class<?> t = applyMixin(IntTarget.class, IntMix.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        // 999999 → handler doubles → 1999998
        assertEquals(1999998, t.getMethod("compute").invoke(inst));
    }

    public static class StringTarget {
        public String greet() { return "hello"; }
    }

    @Mixin("net.echo.hypermixins.MixinModifyConstantTest$StringTarget")
    public static class StringMix {
        @ModifyConstant(method = "greet", constant = @ModifyConstant.Constant(stringValue = "hello"))
        public static String swap(String original) { return original + "-mod"; }
    }

    @Test
    void modifyConstantStringReplacesLdc() throws Exception {
        Class<?> t = applyMixin(StringTarget.class, StringMix.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        assertEquals("hello-mod", t.getMethod("greet").invoke(inst));
    }
}
