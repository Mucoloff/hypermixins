package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Inject;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Overwrite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct coverage of the reflection-fallback path: a {@code @Mixin} class with no KSP-generated
 * {@code $$Descriptor} on the classpath (true for every runtime-test fixture, since this module
 * isn't KSP-processed) must load via {@link MixinDescriptor#fromAnnotations}.
 */
class DescriptorReaderFallbackTest {

    public static class Target {
        public int value() { return 1; }
        public void touch() {}
    }

    @Mixin("net.echo.hypermixins.agent.DescriptorReaderFallbackTest$Target")
    public static class FallbackMixin {
        @Overwrite("value")
        public int value(Object self) { return 2; }

        @Inject(method = "touch", at = @At(point = At.Point.HEAD))
        public void onTouch(Object self) {}
    }

    @Test
    void loadsViaAnnotationFallbackWhenNoDescriptorClass() {
        // No FallbackMixin$$Descriptor exists → DescriptorReader.read falls back to annotations.
        MixinDescriptor d = MixinDescriptor.load(FallbackMixin.class);
        assertEquals("net/echo/hypermixins/agent/DescriptorReaderFallbackTest$Target", d.targetClass());
        assertEquals(1, d.overwrites().size());
        assertEquals("value", d.overwrites().getFirst().targetName());
        assertEquals(1, d.injects().size());
        assertEquals("touch", d.injects().getFirst().targetMethod());
        assertEquals(At.Point.HEAD, d.injects().getFirst().point());
        // Fallback descriptors carry no DSL expressions.
        assertTrue(d.expressions().isEmpty());
        assertFalse(d.overwrites().isEmpty());
    }
}
