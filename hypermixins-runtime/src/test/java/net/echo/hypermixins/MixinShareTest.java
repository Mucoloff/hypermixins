package net.echo.hypermixins;

import net.echo.hypermixins.agent.SharedSlots;
import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Inject;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Ref;
import net.echo.hypermixins.annotations.Share;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MixinShareTest {

    @AfterEach
    void cleanRegistry() {
        MixinRegistry.clearForTests();
        SharedSlots.clearForTests();
    }

    public static volatile String roundTripped;

    public static class ShareTarget {
        public int run() {
            return 7;
        }
    }
    @Mixin("net.echo.hypermixins.MixinShareTest$ShareTarget")
    public static class ShareMixin {
        @Inject(method = "run", at = @At(point = At.Point.HEAD))
        public void onHead(Object self, @Share("payload") Ref<String> cell) {
            cell.set("hello");
        }

        @Inject(method = "run", at = @At(point = At.Point.RETURN))
        public void onReturn(Object self, @Share("payload") Ref<String> cell) {
            roundTripped = cell.get();
        }
    }

    @Test
    void shareRefRoundTripsAcrossHandlers() throws Exception {
        roundTripped = null;
        Class<?> t = applyMixin(ShareTarget.class, ShareMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        assertEquals(7, t.getMethod("run").invoke(inst));
        // HEAD writes "hello", RETURN reads it back.
        assertEquals("hello", roundTripped);
    }
}
