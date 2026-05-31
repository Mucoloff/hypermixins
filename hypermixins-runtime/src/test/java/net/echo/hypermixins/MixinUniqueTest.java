package net.echo.hypermixins;

import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Unique;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MixinUniqueTest {

    @AfterEach
    void cleanRegistry() {
        MixinRegistry.clearForTests();
    }

    public static class UniqueTarget {
        public int compute(int x) { return x; }
    }

    @Mixin("net.echo.hypermixins.MixinUniqueTest$UniqueTarget")
    public static class UniqueMixin {
        @Unique
        public static int helper(int x) { return x * 3; }
    }

    @Test
    void uniqueStaticMethodCopiedIntoTarget() throws Exception {
        Class<?> t = applyMixin(UniqueTarget.class, UniqueMixin.class);
        // Locate the mangled copy on the target.
        Method merged = null;
        for (Method m : t.getDeclaredMethods()) {
            if (m.getName().startsWith("__unique$") && m.getName().contains("helper")) {
                merged = m;
                break;
            }
        }
        assertNotNull(merged, "@Unique helper not merged into target");
        assertTrue(java.lang.reflect.Modifier.isStatic(merged.getModifiers()));
        assertEquals(21, merged.invoke(null, 7));
    }
}
