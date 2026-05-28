package net.echo.hypermixins;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.CallbackInfo;
import net.echo.hypermixins.annotations.CallbackInfoReturnable;
import net.echo.hypermixins.annotations.Inject;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.*;

public class MixinInjectTest {

    /** Side-effect channel — mixin handlers write here so tests can observe activation. */
    public static volatile int counter;
    public static volatile boolean bodyRan;

    @BeforeEach
    void resetCounters() { counter = 0; bodyRan = false; }

    @AfterEach
    void clearRegistry() { MixinRegistry.clearForTests(); }

    // ---- fixtures ----

    public static class HeadTarget {
        public int foo() { bodyRan = true; return 7; }
    }

    @Mixin("net.echo.hypermixins.MixinInjectTest$HeadTarget")
    public static class HeadMixin {
        @Inject(method = "foo", at = @At(point = At.Point.HEAD))
        public void onFoo(Object self) { counter++; }
    }

    public static class TwoReturnsTarget {
        public int branch(int x) {
            if (x > 0) return x;
            return -x;
        }
    }

    @Mixin("net.echo.hypermixins.MixinInjectTest$TwoReturnsTarget")
    public static class TwoReturnsMixin {
        @Inject(method = "branch", at = @At(point = At.Point.RETURN))
        public void onBranch(Object self) { counter++; }
    }

    public static class TailTarget {
        public int once() { return 1; }
    }

    @Mixin("net.echo.hypermixins.MixinInjectTest$TailTarget")
    public static class TailMixin {
        @Inject(method = "once", at = @At(point = At.Point.TAIL))
        public void onOnce(Object self) { counter++; }
    }

    public static class CancelTarget {
        public void run() { bodyRan = true; }
    }

    @Mixin("net.echo.hypermixins.MixinInjectTest$CancelTarget")
    public static class CancelMixin {
        @Inject(method = "run", at = @At(point = At.Point.HEAD), cancellable = true)
        public void onRun(Object self, CallbackInfo ci) { counter++; ci.cancel(); }
    }

    public static class ReturnableTarget {
        public int compute() { bodyRan = true; return 0; }
    }

    @Mixin("net.echo.hypermixins.MixinInjectTest$ReturnableTarget")
    public static class ReturnableMixin {
        @Inject(method = "compute", at = @At(point = At.Point.HEAD), cancellable = true)
        public void onCompute(Object self, CallbackInfoReturnable<Integer> cir) {
            cir.setReturnValue(42);
        }
    }

    // ---- tests ----

    @Test
    void headInjectRuns() throws Exception {
        Class<?> t = applyMixin(HeadTarget.class, HeadMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int result = (int) t.getMethod("foo").invoke(inst);
        assertEquals(7, result);
        assertEquals(1, counter);
        assertTrue(bodyRan);
    }

    @Test
    void returnInjectAllReturns() throws Exception {
        Class<?> t = applyMixin(TwoReturnsTarget.class, TwoReturnsMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        assertEquals(5, t.getMethod("branch", int.class).invoke(inst, 5));
        assertEquals(7, t.getMethod("branch", int.class).invoke(inst, -7));
        assertEquals(2, counter);
    }

    @Test
    void tailInject() throws Exception {
        Class<?> t = applyMixin(TailTarget.class, TailMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        assertEquals(1, t.getMethod("once").invoke(inst));
        assertEquals(1, counter);
    }

    @Test
    void cancellableShortCircuits() throws Exception {
        Class<?> t = applyMixin(CancelTarget.class, CancelMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        t.getMethod("run").invoke(inst);
        assertEquals(1, counter);
        assertFalse(bodyRan, "Target body must not execute when CallbackInfo.cancel() called");
    }

    @Test
    void cancellableReturnable() throws Exception {
        Class<?> t = applyMixin(ReturnableTarget.class, ReturnableMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int result = (int) t.getMethod("compute").invoke(inst);
        assertEquals(42, result);
        assertFalse(bodyRan, "Target body must not execute when setReturnValue() called");
    }
}
