package net.echo.hypermixins;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Definition;
import net.echo.hypermixins.annotations.Expression;
import net.echo.hypermixins.annotations.Inject;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MixinExpressionInstanceofTest {

    @AfterEach
    void cleanRegistry() { MixinRegistry.clearForTests(); }

    public static volatile int hits;

    public static class InstanceofTarget {
        public boolean check(Object o) {
            return o instanceof String;
        }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionInstanceofTest$InstanceofTarget")
    public static class InstanceofMixin {
        @Definition(id = "Str", type = "java/lang/String")
        @Expression("? instanceof Str")
        @Inject(method = "check", at = @At(point = At.Point.EXPRESSION))
        public void onCheck(Object self) { hits++; }
    }

    @Test
    void matchesInstanceofOpcode() throws Exception {
        hits = 0;
        Class<?> t = applyMixin(InstanceofTarget.class, InstanceofMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        boolean out = (boolean) t.getMethod("check", Object.class).invoke(inst, "hi");
        assertEquals(true, out);
        assertTrue(hits >= 1);
    }
}
