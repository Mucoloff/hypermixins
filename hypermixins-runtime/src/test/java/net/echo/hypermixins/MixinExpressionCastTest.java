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

public class MixinExpressionCastTest {

    @AfterEach
    void cleanRegistry() { MixinRegistry.clearForTests(); }

    public static volatile int hits;

    public static class CastTarget {
        public int len(Object o) {
            String s = (String) o;
            return s.length();
        }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionCastTest$CastTarget")
    public static class CastMixin {
        @Definition(id = "Str", type = "java/lang/String")
        @Expression("(Str) ?")
        @Inject(method = "len", at = @At(point = At.Point.EXPRESSION))
        public void onCast(Object self) { hits++; }
    }

    @Test
    void matchesCheckcastOpcode() throws Exception {
        hits = 0;
        Class<?> t = applyMixin(CastTarget.class, CastMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int out = (int) t.getMethod("len", Object.class).invoke(inst, "hello");
        assertEquals(5, out);
        assertTrue(hits >= 1);
    }
}
