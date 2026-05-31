package net.echo.hypermixins;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.WrapWithCondition;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MixinWrapWithConditionTest {

    @AfterEach
    void cleanRegistry() {
        MixinRegistry.clearForTests();
    }

    public static volatile List<String> sink;

    public static class WCondTarget {
        public void run() {
            doIt("alice");
            doIt("bob");
            doIt("eve");
        }
        public void doIt(String who) {
            sink.add(who);
        }
    }
    @Mixin("net.echo.hypermixins.MixinWrapWithConditionTest$WCondTarget")
    public static class WCondMixin {
        @WrapWithCondition(method = "run",
            at = @At(desc = "net/echo/hypermixins/MixinWrapWithConditionTest$WCondTarget.doIt(Ljava/lang/String;)V"))
        public static boolean shouldCall(Object self, String who) {
            return !who.equals("bob");
        }
    }

    @Test
    void wrapWithConditionSkipsSuppressedCalls() throws Exception {
        sink = new ArrayList<>();
        Class<?> t = applyMixin(WCondTarget.class, WCondMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        t.getMethod("run").invoke(inst);
        // bob suppressed, others called.
        assertEquals(List.of("alice", "eve"), sink);
    }
}
