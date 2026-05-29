package net.echo.hypermixins;

import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Overwrite;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Documents the recursion foot-gun: an {@code @Overwrite} handler that calls the original
 * method on {@code self} reflectively re-enters its own INVOKEDYNAMIC call-site. There is no
 * runtime guard — by design, since adding one would cost on every dispatch. Users must route
 * through an {@code @Original}-annotated trampoline instead.
 */
public class MixinRecursionTest {

    public static class Target {
        public int run() { return 1; }
    }

    @Mixin("net.echo.hypermixins.MixinRecursionTest$Target")
    public static class SelfCallingMixin {
        @Overwrite("run")
        public int run(Object self) {
            try {
                // Re-invoke the overwritten method on self → re-enters this very handler.
                Method m = self.getClass().getMethod("run");
                return (int) m.invoke(self);
            } catch (Throwable e) {
                if (e instanceof InvocationTargetException ite && ite.getCause() instanceof StackOverflowError soe) {
                    throw soe;
                }
                if (e instanceof StackOverflowError soe) throw soe;
                throw new RuntimeException(e);
            }
        }
    }

    @AfterEach
    void clear() { MixinRegistry.clearForTests(); }

    @Test
    void overwriteCallingItselfStackOverflows() throws Exception {
        Class<?> t = applyMixin(Target.class, SelfCallingMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
            () -> t.getMethod("run").invoke(inst));
        Throwable root = ex.getCause();
        // Either we hit StackOverflowError directly, or it was wrapped one more time by reflection.
        while (root != null && !(root instanceof StackOverflowError)) root = root.getCause();
        assertEquals(StackOverflowError.class, root != null ? root.getClass() : null,
            "expected the self-call to recurse into StackOverflowError; got: " + ex.getCause());
    }
}
