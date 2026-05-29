package net.echo.hypermixins;

import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Original;
import net.echo.hypermixins.annotations.Overwrite;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static net.echo.hypermixins.TransformerTestSupport.key;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hammer enable / disable against a hot dispatch loop on a separate thread. Asserts only that
 * the call-site never throws and every observed return belongs to the set {{ "mixin-x", "x" }}.
 * Any garbage value or surprise exception would indicate the {@link java.lang.invoke.MutableCallSite}
 * swap is not atomic relative to the in-flight call.
 */
public class MixinRegistryConcurrencyTest {

    public static class Target {
        public String hello(String name) { return name; }
    }

    @Mixin("net.echo.hypermixins.MixinRegistryConcurrencyTest$Target")
    public static class Mix {
        @Original("hello") public native String orig(Object self, String name);
        @Overwrite("hello")
        public String hello(Object self, String name) { return "mixin-" + orig(self, name); }
    }

    @AfterEach
    void reset() { MixinRegistry.clearForTests(); }

    @Test
    void concurrentEnableDisableNeverMisbehaves() throws Exception {
        Class<?> t = applyMixin(Target.class, Mix.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        Method hello = t.getMethod("hello", String.class);
        String k = key(Target.class, "hello", "(Ljava/lang/String;)Ljava/lang/String;");

        // Prime the call-site via lazy bootstrap.
        hello.invoke(inst, "x");

        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Set<String> observed = new HashSet<>();
        CountDownLatch ready = new CountDownLatch(3);

        Thread caller = new Thread(() -> {
            ready.countDown();
            try {
                int i = 0;
                while (!stop.get() && i < 100_000) {
                    Object r = hello.invoke(inst, "x");
                    synchronized (observed) { observed.add((String) r); }
                    i++;
                }
            } catch (Throwable th) { failure.compareAndSet(null, th); }
        }, "caller");

        Thread toggler = new Thread(() -> {
            ready.countDown();
            try {
                while (!stop.get()) {
                    MixinRegistry.disable(k);
                    MixinRegistry.enable(k);
                }
            } catch (Throwable th) { failure.compareAndSet(null, th); }
        }, "toggler");

        Thread chaos = new Thread(() -> {
            ready.countDown();
            try {
                while (!stop.get()) {
                    MixinRegistry.enable(k);
                    MixinRegistry.disable(k);
                }
            } catch (Throwable th) { failure.compareAndSet(null, th); }
        }, "chaos");

        caller.start(); toggler.start(); chaos.start();
        ready.await();
        Thread.sleep(500);
        stop.set(true);

        caller.join(TimeUnit.SECONDS.toMillis(5));
        toggler.join(TimeUnit.SECONDS.toMillis(5));
        chaos.join(TimeUnit.SECONDS.toMillis(5));

        assertNull(failure.get(), () -> "thread failed: " + failure.get());
        assertTrue(observed.contains("x") || observed.contains("mixin-x"),
            "no values observed: " + observed);
        // Every observed value is well-known.
        for (String v : observed) {
            assertTrue("x".equals(v) || "mixin-x".equals(v),
                "unexpected return value seen during enable/disable race: " + v);
        }
    }
}
