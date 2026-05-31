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

    public static class InstanceUniqueTarget {
        public int base() { return 10; }
    }

    @Mixin("net.echo.hypermixins.MixinUniqueTest$InstanceUniqueTarget")
    public static class InstanceUniqueMixin {
        // Self-contained instance helper: no this.<mixin field>, no this.<mixin method>.
        // After merge the body lives on the target as a public static synthetic with
        // (Object self, int x) — slot 0 = self (target instance), slot 1 = x (shifted from 0 → 1).
        @Unique
        public int doubled(int x) { return x * 2; }
    }

    @Test
    void instanceUniqueClonesWithSelfPrepended() throws Exception {
        Class<?> t = applyMixin(InstanceUniqueTarget.class, InstanceUniqueMixin.class);
        Method merged = null;
        for (Method m : t.getDeclaredMethods()) {
            if (m.getName().startsWith("__unique$") && m.getName().contains("doubled")) {
                merged = m;
                break;
            }
        }
        assertNotNull(merged, "instance @Unique helper not merged into target");
        assertTrue(java.lang.reflect.Modifier.isStatic(merged.getModifiers()));
        // Descriptor has Object self prepended: (Object, int) → int.
        assertEquals(2, merged.getParameterCount());
        assertEquals(Object.class, merged.getParameterTypes()[0]);
        assertEquals(int.class, merged.getParameterTypes()[1]);
        Object instance = t.getDeclaredConstructor().newInstance();
        assertEquals(14, merged.invoke(null, instance, 7));
    }
}
