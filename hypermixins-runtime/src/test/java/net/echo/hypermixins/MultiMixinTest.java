package net.echo.hypermixins;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Inject;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Original;
import net.echo.hypermixins.annotations.Overwrite;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixins;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Two independent mixin classes targeting the same target class — covers the typical
 * mod-stacking case where multiple plugins each contribute one handler to a shared target.
 */
public class MultiMixinTest {

    public static volatile int injectCounter;

    @BeforeEach void resetCounter() { injectCounter = 0; }
    @AfterEach  void clearRegistry() { MixinRegistry.clearForTests(); }

    // ---- fixtures ----

    public static class SharedTarget {
        public String greet(String name) { return "hi-" + name; }
        public void notify(String msg) { /* no-op */ }
    }

    @Mixin("net.echo.hypermixins.MultiMixinTest$SharedTarget")
    public static class OverwriteMixin {
        @Original("greet") public native String origGreet(Object self, String name);
        @Overwrite("greet")
        public String greet(Object self, String name) { return "[A]" + origGreet(self, name); }
    }

    @Mixin("net.echo.hypermixins.MultiMixinTest$SharedTarget")
    public static class InjectMixin {
        @Inject(method = "notify", at = @At(point = At.Point.HEAD))
        public void onNotify(Object self) { injectCounter++; }
    }

    // ---- tests ----

    @Test
    void overwriteAndInjectCoexist() throws Exception {
        Class<?> t = applyMixins(SharedTarget.class, OverwriteMixin.class, InjectMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();

        // OverwriteMixin's wrapper fires.
        assertEquals("[A]hi-bob", t.getMethod("greet", String.class).invoke(inst, "bob"));

        // InjectMixin's HEAD handler fires on notify.
        t.getMethod("notify", String.class).invoke(inst, "ping");
        assertEquals(1, injectCounter);
    }

    // ---- stacked injects ----

    public static volatile java.util.List<String> injectOrder;

    public static class StackedTarget {
        public void step() { injectOrder.add("body"); }
    }

    @Mixin("net.echo.hypermixins.MultiMixinTest$StackedTarget")
    public static class FirstInjectMixin {
        @Inject(method = "step", at = @At(point = At.Point.HEAD))
        public void onStep(Object self) { injectOrder.add("A"); }
    }

    @Mixin("net.echo.hypermixins.MultiMixinTest$StackedTarget")
    public static class SecondInjectMixin {
        @Inject(method = "step", at = @At(point = At.Point.HEAD))
        public void onStep(Object self) { injectOrder.add("B"); }
    }

    @Test
    void stackedHeadInjectsRunInRegistrationOrder() throws Exception {
        injectOrder = new java.util.ArrayList<>();
        Class<?> t = applyMixins(StackedTarget.class, FirstInjectMixin.class, SecondInjectMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        t.getMethod("step").invoke(inst);
        // First mapping inserts at HEAD, then the second mapping inserts again at HEAD —
        // the second insertion lands before the first one's block, so call order is B, A, body.
        assertEquals(java.util.List.of("B", "A", "body"), injectOrder);
    }
}
