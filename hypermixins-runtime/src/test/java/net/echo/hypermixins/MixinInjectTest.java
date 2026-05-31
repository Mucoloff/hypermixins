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

    // ---- local-capture fixtures ----

    public static volatile int capturedInt;
    public static volatile String capturedString;

    public static class CaptureTarget {
        public int foo(int x, String tag) { bodyRan = true; return x; }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$CaptureTarget")
    public static class CaptureMixin {
        @Inject(method = "foo", at = @At(point = At.Point.HEAD))
        public void onFoo(Object self, int x, String tag) {
            capturedInt = x;
            capturedString = tag;
        }
    }

    public static class CaptureCancelTarget {
        public int compute(int x) { bodyRan = true; return x * 2; }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$CaptureCancelTarget")
    public static class CaptureCancelMixin {
        @Inject(method = "compute", at = @At(point = At.Point.HEAD), cancellable = true)
        public void onCompute(Object self, int x, CallbackInfoReturnable<Integer> cir) {
            if (x < 0) cir.setReturnValue(-1);
        }
    }

    @Test
    void captureForwardsTargetParams() throws Exception {
        Class<?> t = applyMixin(CaptureTarget.class, CaptureMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int result = (int) t.getMethod("foo", int.class, String.class).invoke(inst, 42, "hi");
        assertEquals(42, result);
        assertEquals(42, capturedInt);
        assertEquals("hi", capturedString);
        assertTrue(bodyRan);
    }

    @Test
    void captureWithCancellable() throws Exception {
        Class<?> t = applyMixin(CaptureCancelTarget.class, CaptureCancelMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        // x >= 0 → not cancelled, body runs.
        assertEquals(10, t.getMethod("compute", int.class).invoke(inst, 5));
        assertTrue(bodyRan);
        bodyRan = false;
        // x < 0 → cancelled, returns -1.
        assertEquals(-1, t.getMethod("compute", int.class).invoke(inst, -3));
        assertFalse(bodyRan);
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

    // ---- exception propagation (uses JDK exception classes to avoid loader-cross issues) ----

    public static class BoomHeadTarget {
        public int run() { bodyRan = true; return 1; }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$BoomHeadTarget")
    public static class BoomHeadMixin {
        @Inject(method = "run", at = @At(point = At.Point.HEAD))
        public void onRun(Object self) { throw new IllegalStateException("inject-head-boom"); }
    }

    public static class BoomCancellableTarget {
        public void run() { bodyRan = true; }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$BoomCancellableTarget")
    public static class BoomCancellableMixin {
        @Inject(method = "run", at = @At(point = At.Point.HEAD), cancellable = true)
        public void onRun(Object self, CallbackInfo ci) {
            throw new IllegalStateException("before-cancel");
        }
    }

    @Test
    void injectHandlerExceptionPropagates() throws Exception {
        Class<?> t = applyMixin(BoomHeadTarget.class, BoomHeadMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        var ex = assertThrows(java.lang.reflect.InvocationTargetException.class,
            () -> t.getMethod("run").invoke(inst));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertEquals("inject-head-boom", ex.getCause().getMessage());
        assertFalse(bodyRan, "target body must not run when HEAD inject throws");
    }

    @Test
    void cancellableHandlerExceptionPropagatesBeforeCancelTakesEffect() throws Exception {
        Class<?> t = applyMixin(BoomCancellableTarget.class, BoomCancellableMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        var ex = assertThrows(java.lang.reflect.InvocationTargetException.class,
            () -> t.getMethod("run").invoke(inst));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertEquals("before-cancel", ((IllegalStateException) ex.getCause()).getMessage());
        assertFalse(bodyRan);
    }

    // ---- @Local capture ----

    public static volatile int localCaptured;

    public static class LocalTarget {
        public int run(int x) {
            return x + 1;
        }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$LocalTarget")
    public static class LocalMixin {
        @Inject(method = "run", at = @At(point = At.Point.HEAD))
        public void onRun(Object self, @net.echo.hypermixins.annotations.Local(index = 1) int x) {
            localCaptured = x;
        }
    }

    // ---- @At#shift = AFTER ----

    public static volatile java.util.List<String> shiftOrder;

    public static class ShiftTarget {
        public int run() {
            int x = 999999;
            return x + 1;
        }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$ShiftTarget")
    public static class ShiftMixin {
        @Inject(method = "run", at = @At(point = At.Point.CONSTANT, desc = "I:999999",
            shift = At.Shift.AFTER))
        public void onAfter(Object self) { shiftOrder.add("after"); }
    }

    @Test
    void shiftAfterRunsAfterMatchedInstruction() throws Exception {
        shiftOrder = new java.util.ArrayList<>();
        Class<?> t = applyMixin(ShiftTarget.class, ShiftMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        assertEquals(1_000_000, t.getMethod("run").invoke(inst));
        assertEquals(java.util.List.of("after"), shiftOrder);
    }

    // ---- @At#desc wildcard ----

    public static volatile int wildcardCalls;

    public static class WildcardTarget {
        public String run() {
            String a = String.valueOf(1);
            String b = String.valueOf(2);
            return a + b;
        }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$WildcardTarget")
    public static class WildcardMixin {
        @Inject(method = "run", at = @At(point = At.Point.INVOKE,
            desc = "java/lang/String.valueOf*"))
        public void onAny(Object self) { wildcardCalls++; }
    }

    @Test
    void atDescWildcardMatchesAllOccurrences() throws Exception {
        wildcardCalls = 0;
        Class<?> t = applyMixin(WildcardTarget.class, WildcardMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        assertEquals("12", t.getMethod("run").invoke(inst));
        // Two String.valueOf calls match the wildcard.
        assertEquals(2, wildcardCalls);
    }

    // ---- @Slice on @Inject ----

    public static volatile int sliceCalls;

    public static class SliceTarget {
        public String run() {
            String a = String.valueOf(1);   // first valueOf
            int marker = System.identityHashCode(a);
            String b = String.valueOf(2);   // second valueOf
            return a + b + marker;
        }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$SliceTarget")
    public static class SliceMixin {
        @Inject(method = "run", at = @At(point = At.Point.INVOKE,
            desc = "java/lang/String.valueOf*"))
        @net.echo.hypermixins.annotations.Slice(
            from = @At(point = At.Point.INVOKE, desc = "java/lang/System.identityHashCode(Ljava/lang/Object;)I"))
        public void onSecondOnly(Object self) { sliceCalls++; }
    }

    @Test
    void sliceConstrainsInjectSiteToWindow() throws Exception {
        sliceCalls = 0;
        Class<?> t = applyMixin(SliceTarget.class, SliceMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        t.getMethod("run").invoke(inst);
        // Without @Slice the wildcard matches both valueOf calls (2). With @Slice
        // from = identityHashCode, only the second valueOf is in the window.
        assertEquals(1, sliceCalls);
    }

    // ---- @At#desc regex ----

    public static volatile int regexCalls;

    public static class RegexTarget {
        public String run() {
            String a = String.valueOf(7);
            String b = Integer.toString(8);
            return a + b;
        }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$RegexTarget")
    public static class RegexMixin {
        @Inject(method = "run", at = @At(point = At.Point.INVOKE,
            desc = "regex:java/lang/.*\\.(valueOf|toString)\\(I\\)Ljava/lang/String;"))
        public void onAny(Object self) { regexCalls++; }
    }

    @Test
    void atDescRegexMatchesAcrossOwners() throws Exception {
        regexCalls = 0;
        Class<?> t = applyMixin(RegexTarget.class, RegexMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        assertEquals("78", t.getMethod("run").invoke(inst));
        // Two int→String conversions match the regex.
        assertEquals(2, regexCalls);
    }

    // ---- @At.Point.NEW ----

    public static volatile int newAllocCounter;

    public static class NewAllocTarget {
        public Object allocate() {
            return new java.util.HashMap<>();
        }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$NewAllocTarget")
    public static class NewAllocMixin {
        @Inject(method = "allocate", at = @At(point = At.Point.NEW, desc = "java/util/HashMap"))
        public void onAlloc(Object self) { newAllocCounter++; }
    }

    @Test
    void newAllocationInjectFires() throws Exception {
        newAllocCounter = 0;
        Class<?> t = applyMixin(NewAllocTarget.class, NewAllocMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        Object result = t.getMethod("allocate").invoke(inst);
        assertNotNull(result);
        assertEquals(1, newAllocCounter);
    }

    // ---- @Local(ordinal = K) ----

    public static volatile int ordinalCaptured;

    public static class OrdinalTarget {
        public int run(String tag, int a, int b) {
            return a + b + tag.length();
        }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$OrdinalTarget")
    public static class OrdinalMixin {
        @Inject(method = "run", at = @At(point = At.Point.HEAD))
        public void onRun(Object self, @net.echo.hypermixins.annotations.Local(ordinal = 1) int second) {
            ordinalCaptured = second;
        }
    }

    @Test
    void captureLocalByOrdinal() throws Exception {
        Class<?> t = applyMixin(OrdinalTarget.class, OrdinalMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int result = (int) t.getMethod("run", String.class, int.class, int.class).invoke(inst, "hi", 10, 20);
        assertEquals(32, result);
        // Second int target param → 20.
        assertEquals(20, ordinalCaptured);
    }

    // ---- @Local(argsOnly = true) writeback ----

    public static class ArgsOnlyTarget {
        public int run(int x) {
            return x * 2;
        }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$ArgsOnlyTarget")
    public static class ArgsOnlyMixin {
        @Inject(method = "run", at = @At(point = At.Point.HEAD))
        public void onRun(Object self,
                          @net.echo.hypermixins.annotations.Local(argsOnly = true) int[] x) {
            x[0] = x[0] + 100;
        }
    }

    @Test
    void captureLocalArgsOnlyWritesBack() throws Exception {
        Class<?> t = applyMixin(ArgsOnlyTarget.class, ArgsOnlyMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        // handler shifts x by +100 → run sees 5 + 100 = 105 → returns 210
        assertEquals(210, t.getMethod("run", int.class).invoke(inst, 5));
    }

    // ---- bare @Local (unique-type auto-pick) ----

    public static volatile String autoPickCaptured;

    public static class BareTarget {
        public int run(String tag, int n) { return n + tag.length(); }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$BareTarget")
    public static class BareMixin {
        @Inject(method = "run", at = @At(point = At.Point.HEAD))
        public void onRun(Object self, @net.echo.hypermixins.annotations.Local String tag) {
            autoPickCaptured = tag;
        }
    }

    @Test
    void captureLocalUniqueTypeAutoPick() throws Exception {
        Class<?> t = applyMixin(BareTarget.class, BareMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int result = (int) t.getMethod("run", String.class, int.class).invoke(inst, "ab", 5);
        assertEquals(7, result);
        assertEquals("ab", autoPickCaptured);
    }

    @Test
    void captureLocalByIndex() throws Exception {
        Class<?> t = applyMixin(LocalTarget.class, LocalMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int result = (int) t.getMethod("run", int.class).invoke(inst, 41);
        assertEquals(42, result);
        assertEquals(41, localCaptured);
    }

    // ---- @Cancellable shorthand ----

    public static class CancellableShorthandTarget {
        public int compute(int x) { return x; }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$CancellableShorthandTarget")
    public static class CancellableShorthandMixin {
        @Inject(method = "compute", at = @At(point = At.Point.HEAD))
        @net.echo.hypermixins.annotations.Cancellable
        public void onCompute(Object self, int x, CallbackInfoReturnable<Integer> cir) {
            if (x < 0) cir.setReturnValue(-100);
        }
    }

    @Test
    void cancellableAnnotationShorthandActsLikeFlag() throws Exception {
        Class<?> t = applyMixin(CancellableShorthandTarget.class, CancellableShorthandMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        assertEquals(-100, t.getMethod("compute", int.class).invoke(inst, -5));
        assertEquals(7, t.getMethod("compute", int.class).invoke(inst, 7));
    }

    // ---- bare @Local at non-HEAD point (Analyzer-driven) ----

    public static volatile String siteFrameCaptured;

    public static class SiteFrameTarget {
        public int run(int seed) {
            String midLocal = "tag-" + seed;
            return Integer.parseInt(midLocal.substring(4));
        }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$SiteFrameTarget")
    public static class SiteFrameMixin {
        @Inject(method = "run", at = @At(point = At.Point.INVOKE,
            desc = "java/lang/Integer.parseInt(Ljava/lang/String;)I"))
        public void onParse(Object self, @net.echo.hypermixins.annotations.Local String midLocal) {
            siteFrameCaptured = midLocal;
        }
    }

    @Test
    void captureLocalAtInvokePointResolvesMidMethodSlot() throws Exception {
        Class<?> t = applyMixin(SiteFrameTarget.class, SiteFrameMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int result = (int) t.getMethod("run", int.class).invoke(inst, 42);
        assertEquals(42, result);
        assertEquals("tag-42", siteFrameCaptured);
    }

    // ---- @Local(ordinal = K) at non-HEAD point ----

    public static volatile int siteOrdinalCaptured;

    public static class SiteOrdinalTarget {
        public int run(int seed) {
            int first = seed + 1;
            int second = seed + 2;
            return Math.max(first, second);
        }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$SiteOrdinalTarget")
    public static class SiteOrdinalMixin {
        @Inject(method = "run", at = @At(point = At.Point.INVOKE,
            desc = "java/lang/Math.max(II)I"))
        public void onMax(Object self,
                          @net.echo.hypermixins.annotations.Local(ordinal = 1) int secondMid) {
            siteOrdinalCaptured = secondMid;
        }
    }

    // ---- bare @Local at RETURN binding mid-method local ----

    public static volatile String siteReturnCaptured;

    public static class SiteReturnTarget {
        public int run(int seed) {
            String tag = "return-" + seed;
            return tag.length();
        }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$SiteReturnTarget")
    public static class SiteReturnMixin {
        @Inject(method = "run", at = @At(point = At.Point.RETURN))
        public void onReturn(Object self, @net.echo.hypermixins.annotations.Local String tag) {
            siteReturnCaptured = tag;
        }
    }

    @Test
    void captureMidMethodLocalAtReturnPoint() throws Exception {
        Class<?> t = applyMixin(SiteReturnTarget.class, SiteReturnMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int result = (int) t.getMethod("run", int.class).invoke(inst, 42);
        // "return-42".length() = 9
        assertEquals(9, result);
        assertEquals("return-42", siteReturnCaptured);
    }

    // ---- @Local at FIELD point ----

    public static volatile String siteFieldCaptured;

    public static class SiteFieldTarget {
        public java.io.PrintStream sink = System.out;
        public int read() {
            String tag = "field-read";
            return sink.hashCode() + tag.length();
        }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$SiteFieldTarget")
    public static class SiteFieldMixin {
        @Inject(method = "read", at = @At(point = At.Point.FIELD,
            desc = "net/echo/hypermixins/MixinInjectTest$SiteFieldTarget.sink:Ljava/io/PrintStream;"))
        public void onFieldRead(Object self, @net.echo.hypermixins.annotations.Local String tag) {
            siteFieldCaptured = tag;
        }
    }

    @Test
    void captureLocalAtFieldPointResolvesMidMethodSlot() throws Exception {
        Class<?> t = applyMixin(SiteFieldTarget.class, SiteFieldMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int result = (int) t.getMethod("read").invoke(inst);
        // tag is alive across the GETFIELD insn.
        assertEquals("field-read", siteFieldCaptured);
        // Just sanity that read() still produces a value.
        assertNotNull(Integer.valueOf(result));
    }

    // ---- @Local(argsOnly = true) at non-HEAD point ----

    public static class SiteArgsOnlyTarget {
        public int run(int seed) {
            int mid = seed + 1;
            return Math.abs(mid);
        }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$SiteArgsOnlyTarget")
    public static class SiteArgsOnlyMixin {
        @Inject(method = "run", at = @At(point = At.Point.INVOKE,
            desc = "java/lang/Math.abs(I)I"))
        public void onAbs(Object self,
                          @net.echo.hypermixins.annotations.Local(argsOnly = true, ordinal = 1) int[] mid) {
            mid[0] = mid[0] + 100;
        }
    }

    @Test
    void argsOnlyLocalAtInvokePointWritesBackMidMethodSlot() throws Exception {
        Class<?> t = applyMixin(SiteArgsOnlyTarget.class, SiteArgsOnlyMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        // handler bumps mid (ordinal 1 = first non-param int local = mid) by +100.
        // run computes Math.abs(seed + 1 + 100) = abs(7 + 1 + 100) = 108.
        assertEquals(108, t.getMethod("run", int.class).invoke(inst, 7));
    }

    // ---- @Local at JUMP point ----

    public static volatile int siteJumpCaptured;

    public static class SiteJumpTarget {
        public int run(int seed) {
            int threshold = seed * 2;
            if (threshold > 50) return 1;
            return 0;
        }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$SiteJumpTarget")
    public static class SiteJumpMixin {
        @Inject(method = "run", at = @At(point = At.Point.JUMP))
        public void onJump(Object self, @net.echo.hypermixins.annotations.Local(ordinal = 1) int threshold) {
            siteJumpCaptured = threshold;
        }
    }

    @Test
    void captureLocalAtJumpPoint() throws Exception {
        Class<?> t = applyMixin(SiteJumpTarget.class, SiteJumpMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int result = (int) t.getMethod("run", int.class).invoke(inst, 30);
        assertEquals(1, result);
        assertEquals(60, siteJumpCaptured);
    }

    // ---- @Local at CONSTANT point ----

    public static volatile String siteConstantCaptured;

    public static class SiteConstantTarget {
        public int run() {
            String label = "constant-tag";
            return 1234567 + label.length();
        }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$SiteConstantTarget")
    public static class SiteConstantMixin {
        @Inject(method = "run", at = @At(point = At.Point.CONSTANT, desc = "I:1234567"))
        public void onConstant(Object self, @net.echo.hypermixins.annotations.Local String label) {
            siteConstantCaptured = label;
        }
    }

    @Test
    void captureLocalAtConstantPoint() throws Exception {
        Class<?> t = applyMixin(SiteConstantTarget.class, SiteConstantMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int result = (int) t.getMethod("run").invoke(inst);
        assertEquals(1234567 + "constant-tag".length(), result);
        assertEquals("constant-tag", siteConstantCaptured);
    }

    // ---- @Local at NEW point ----

    public static volatile String siteNewCaptured;

    public static class SiteNewTarget {
        public java.util.List<String> run() {
            String seedTag = "new-tag";
            return new java.util.ArrayList<>(java.util.List.of(seedTag));
        }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$SiteNewTarget")
    public static class SiteNewMixin {
        @Inject(method = "run", at = @At(point = At.Point.NEW, desc = "java/util/ArrayList"))
        public void onAlloc(Object self, @net.echo.hypermixins.annotations.Local String seedTag) {
            siteNewCaptured = seedTag;
        }
    }

    @Test
    void captureLocalAtNewPoint() throws Exception {
        Class<?> t = applyMixin(SiteNewTarget.class, SiteNewMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        Object result = t.getMethod("run").invoke(inst);
        assertNotNull(result);
        assertEquals("new-tag", siteNewCaptured);
    }

    // ---- bare @Local ambiguity at non-HEAD point ----

    public static class SiteAmbiguousTarget {
        public int run(int seed) {
            int first = seed + 1;
            int second = seed + 2;
            return Math.max(first, second);
        }
    }
    @Mixin("net.echo.hypermixins.MixinInjectTest$SiteAmbiguousTarget")
    public static class SiteAmbiguousMixin {
        @Inject(method = "run", at = @At(point = At.Point.INVOKE,
            desc = "java/lang/Math.max(II)I"))
        public void onMax(Object self, @net.echo.hypermixins.annotations.Local int picked) {}
    }

    @Test
    void bareLocalAtNonHeadFailsOnAmbiguity() {
        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> applyMixin(SiteAmbiguousTarget.class, SiteAmbiguousMixin.class));
        org.junit.jupiter.api.Assertions.assertTrue(
            ex.getMessage().contains("ambiguous"),
            () -> "expected ambiguity message, got: " + ex.getMessage());
    }

    @Test
    void captureLocalOrdinalAtInvokePoint() throws Exception {
        Class<?> t = applyMixin(SiteOrdinalTarget.class, SiteOrdinalMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int result = (int) t.getMethod("run", int.class).invoke(inst, 10);
        assertEquals(12, result);
        // ordinal 0 = seed (param), ordinal 1 = first, ordinal 2 = second.
        // Live int locals at Math.max site in declaration order: seed, first, second.
        // ordinal = 1 → first → 11.
        assertEquals(11, siteOrdinalCaptured);
    }
}
