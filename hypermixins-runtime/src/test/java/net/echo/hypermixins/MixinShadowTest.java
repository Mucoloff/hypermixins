package net.echo.hypermixins;

import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Overwrite;
import net.echo.hypermixins.annotations.Shadow;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises {@code @Shadow} method forwarding: the mixin declares a {@code native} shim that
 * the transformer rewrites into a {@code CHECKCAST + INVOKEVIRTUAL} on the target. The shim is
 * then called from an {@code @Overwrite} handler to reach a sibling target method.
 */
public class MixinShadowTest {

    public static class Target {
        public String greet(String name) { return "hi-" + name; }
        public String tag()               { return "tagged"; }
    }

    @Mixin("net.echo.hypermixins.MixinShadowTest$Target")
    public static class TagShadowingMixin {
        @Shadow("tag")
        public native String tag(Object self);

        @Overwrite("greet")
        public String greet(Object self, String name) { return tag(self) + "/" + name; }
    }

    @AfterEach
    void reset() { MixinRegistry.clearForTests(); }

    @Test
    void shadowForwardsToTargetMethod() throws Exception {
        Class<?> t = applyMixin(Target.class, TagShadowingMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        String result = (String) t.getMethod("greet", String.class).invoke(inst, "alice");
        assertEquals("tagged/alice", result);
    }
}
