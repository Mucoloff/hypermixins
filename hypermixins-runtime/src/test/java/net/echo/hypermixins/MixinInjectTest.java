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

    // ---- INVOKE / FIELD / CONSTANT / JUMP fixtures ----

    public static class InvokeTarget {
        public int run() {
            String s = String.valueOf(123);
            return s.length();
        }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$InvokeTarget")
    public static class InvokeMixin {
        @Inject(method = "run", at = @At(point = At.Point.INVOKE,
            desc = "java/lang/String.valueOf(I)Ljava/lang/String;"))
        public void onInvoke(Object self) { counter++; }
    }

    public static class FieldTarget {
        public int x = 5;
        public int read() { return x + x; } // two GETFIELDs
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$FieldTarget")
    public static class FieldMixin {
        @Inject(method = "read", at = @At(point = At.Point.FIELD,
            desc = "net/echo/hypermixins/MixinInjectTest$FieldTarget.x:I"))
        public void onField(Object self) { counter++; }
    }

    public static class JumpTarget {
        public int branch(int x) {
            if (x > 0) return 1;
            return -1;
        }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$JumpTarget")
    public static class JumpMixin {
        @Inject(method = "branch", at = @At(point = At.Point.JUMP))
        public void onJump(Object self) { counter++; }
    }

    // ---- INVOKE / FIELD / CONSTANT / JUMP tests ----

    @Test
    void invokeInject() throws Exception {
        Class<?> t = applyMixin(InvokeTarget.class, InvokeMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        assertEquals(3, t.getMethod("run").invoke(inst));
        assertEquals(1, counter);
    }

    @Test
    void fieldInject() throws Exception {
        Class<?> t = applyMixin(FieldTarget.class, FieldMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        assertEquals(10, t.getMethod("read").invoke(inst));
        // Two GETFIELDs on `x` → handler fires twice.
        assertEquals(2, counter);
    }

    public static class LdcConstantTarget {
        public int big() { return 1234567; } // forces LDC
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$LdcConstantTarget")
    public static class LdcConstantMixin {
        @Inject(method = "big", at = @At(point = At.Point.CONSTANT, desc = "I:1234567"))
        public void onConst(Object self) { counter++; }
    }

    @Test
    void constantInjectLdc() throws Exception {
        Class<?> t = applyMixin(LdcConstantTarget.class, LdcConstantMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        assertEquals(1234567, t.getMethod("big").invoke(inst));
        assertEquals(1, counter);
    }

    @Test
    void jumpInject() throws Exception {
        Class<?> t = applyMixin(JumpTarget.class, JumpMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        assertEquals(1, t.getMethod("branch", int.class).invoke(inst, 5));
        // One conditional jump (the if).
        assertEquals(1, counter);
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
