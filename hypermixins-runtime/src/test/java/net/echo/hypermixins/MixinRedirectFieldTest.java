package net.echo.hypermixins;

import net.echo.hypermixins.agent.MixinDescriptor;
import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Call;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Redirect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the current behaviour around field-style {@code @Redirect} configurations.
 * The {@link Call} enum exposes GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC values, but the
 * transformer's {@code applyRedirects} only walks {@link org.objectweb.asm.tree.MethodInsnNode}s
 * and the descriptor loader rejects field-shaped {@code @At#desc()} strings (no opening paren).
 * This test locks the rejection in place; supporting field redirects is tracked in the backlog.
 */
public class MixinRedirectFieldTest {

    public static class Target {
        public int count;
        public void bump() { count++; }
    }

    @Mixin("net.echo.hypermixins.MixinRedirectFieldTest$Target")
    public static class FieldRedirectMixin {
        @Redirect(
            method = "bump",
            at = @At(desc = "net/echo/hypermixins/MixinRedirectFieldTest$Target.count:I", call = Call.GETFIELD)
        )
        public static int rerouted(Object owner) { return 42; }
    }

    @Test
    void fieldShapedAtDescIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> MixinDescriptor.fromAnnotations(FieldRedirectMixin.class));
        // Either of these phrasings is acceptable as a stable rejection signal.
        String msg = ex.getMessage();
        assertTrue(msg.contains("(") || msg.toLowerCase().contains("paren"),
            "expected paren-missing diagnostic, got: " + msg);
    }
}
