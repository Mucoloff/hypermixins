package net.echo.hypermixins.api;

import net.echo.hypermixins.registry.MixinRegistry;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.Objects;

/**
 * Handle returned by {@link HyperMixins#register} representing a single registered mixin.
 * <p>
 * Allows the mixin to be disabled/enabled at runtime without retransforming bytecode —
 * the dispatch flips atomically via {@link MixinRegistry} {@link java.lang.invoke.MutableCallSite}.
 * <p>
 * {@link #unregister()} removes the transformer from the Instrumentation chain and disables
 * all call-sites. Classes already transformed retain their {@code __mixin$} field and
 * {@code __original$} methods (schema cannot be rolled back via
 * {@link Instrumentation#retransformClasses}).
 *
 * @author xEcho1337
 */
public final class MixinHandle {

    private final List<String> keys;
    private final Instrumentation inst;
    private final ClassFileTransformer transformer;
    private volatile boolean active;

    private MixinHandle(Instrumentation inst, ClassFileTransformer transformer, List<String> keys) {
        this.inst = Objects.requireNonNull(inst, "inst");
        this.transformer = Objects.requireNonNull(transformer, "transformer");
        this.keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
        this.active = true;
    }

    /** Factory — used by {@link HyperMixins} after registering a mixin. */
    public static MixinHandle of(Instrumentation inst, ClassFileTransformer transformer, List<String> keys) {
        return new MixinHandle(inst, transformer, keys);
    }

    /**
     * Disables all call-sites managed by this handle. The target class methods fall back
     * to their original implementations. No bytecode retransformation occurs.
     */
    public void disable() {
        if (!active) return;
        keys.forEach(MixinRegistry::disable);
        active = false;
    }

    /**
     * Re-enables all call-sites managed by this handle, restoring the mixin handlers.
     * No bytecode retransformation occurs.
     */
    public void enable() {
        if (active) return;
        keys.forEach(MixinRegistry::enable);
        active = true;
    }

    /**
     * Disables the mixin and removes its transformer from the Instrumentation chain.
     * Future class loads/retransforms will not apply this mixin.
     * Already-transformed classes retain the injected schema (field + shadow method).
     */
    public void unregister() {
        disable();
        inst.removeTransformer(transformer);
    }

    /** Whether this handle is currently active (mixin handlers are in use). */
    public boolean isActive() {
        return active;
    }

    /** The call-site keys managed by this handle. */
    public List<String> keys() {
        return keys;
    }
}
