package net.echo.hypermixins;

import net.echo.hypermixins.annotations.Implements;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Overwrite;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MixinImplementsTest {

    @AfterEach
    void cleanRegistry() {
        MixinRegistry.clearForTests();
    }

    public static volatile String runRecorded;

    public static class RunnableTarget {
        public void run() {}
    }

    @Mixin("net.echo.hypermixins.MixinImplementsTest$RunnableTarget")
    @Implements({ Runnable.class })
    public static class RunnableMixin {
        @Overwrite("run")
        public void run(Object self) { runRecorded = "ran"; }
    }

    @Test
    void implementsAddsInterfaceToTarget() throws Exception {
        Class<?> t = applyMixin(RunnableTarget.class, RunnableMixin.class);
        assertTrue(Runnable.class.isAssignableFrom(t),
            "target should implement Runnable after @Implements");

        runRecorded = null;
        Object inst = t.getDeclaredConstructor().newInstance();
        ((Runnable) inst).run();
        assertEquals("ran", runRecorded);
    }
}
