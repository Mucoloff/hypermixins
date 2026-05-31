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

public class MixinExpressionChainedTest {

    @AfterEach
    void cleanRegistry() {
        MixinRegistry.clearForTests();
    }

    public static volatile String captured;
    public static volatile int hits;

    public static class Session {
        public void write(String s) {}
    }

    public static class Store {
        private final Session sess = new Session();
        public Session session() { return sess; }
    }

    public static class ChainedTarget {
        private final Store store = new Store();

        public void run(String msg) {
            store.session().write(msg);
        }
    }

    @Mixin("net.echo.hypermixins.MixinExpressionChainedTest$ChainedTarget")
    public static class ChainedMixin {
        @Definition(id = "session",
            method = "net/echo/hypermixins/MixinExpressionChainedTest$Store.session()Lnet/echo/hypermixins/MixinExpressionChainedTest$Session;")
        @Definition(id = "write",
            method = "net/echo/hypermixins/MixinExpressionChainedTest$Session.write(Ljava/lang/String;)V")
        @Expression("session().write(?)")
        @Inject(method = "run", at = @At(point = At.Point.EXPRESSION))
        public void onWrite(Object self, String msg) {
            captured = msg;
            hits++;
        }
    }

    @Test
    void chainedReceiverMatchesNestedCall() throws Exception {
        captured = null;
        hits = 0;
        Class<?> t = applyMixin(ChainedTarget.class, ChainedMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        t.getMethod("run", String.class).invoke(inst, "hello");
        assertEquals("hello", captured);
        assertEquals(1, hits);
    }
}
