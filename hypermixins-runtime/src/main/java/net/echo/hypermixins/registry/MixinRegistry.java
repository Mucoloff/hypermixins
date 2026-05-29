package net.echo.hypermixins.registry;

import java.lang.invoke.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry mapping per-method call-site keys to {@link MutableCallSite}s.
 * <p>
 * Key format: {@code "owner/Internal#methodName(params)ret"}.
 * Each overwritten method is assigned a key; the call-site target switches atomically
 * between mixin handler and {@code __original$} delegate via {@link #enable}/{@link #disable}.
 * <p>
 * Lazy installation: if handles are not yet installed at bootstrap time (e.g., class loaded
 * before {@code installHandles} was called), the bootstrap uses the JVM-provided
 * {@link MethodHandles.Lookup} to resolve {@code __original$} and {@code __dispatch$} methods.
 */
public final class MixinRegistry {

    private static final Map<String, MutableCallSite> SITES    = new ConcurrentHashMap<>();
    private static final Map<String, MethodHandle>    ORIGINALS = new ConcurrentHashMap<>();
    private static final Map<String, MethodHandle>    MIXINS    = new ConcurrentHashMap<>();

    /** Pending lazy installs: key → [originalMethodName, dispatchMethodName]. */
    static final Map<String, String[]> PENDING = new ConcurrentHashMap<>();

    private MixinRegistry() {}

    /**
     * Bootstrap method invoked by {@code INVOKEDYNAMIC} in transformed target methods.
     * Resolves (or lazily installs) the {@link MutableCallSite} for the given key.
     */
    public static CallSite bootstrap(MethodHandles.Lookup lookup, String name, MethodType type, String key) {
        MutableCallSite cs = SITES.computeIfAbsent(key, k -> new MutableCallSite(type));
        if (!MIXINS.containsKey(key) && !ORIGINALS.containsKey(key)) {
            tryLazyInstall(lookup, type, key);
        }
        MethodHandle initial = MIXINS.getOrDefault(key, ORIGINALS.get(key));
        if (initial != null) cs.setTarget(initial.asType(type));
        return cs;
    }

    /**
     * Registers a pending lazy-install entry for {@code key}. Called by the transformer when
     * it emits an INVOKEDYNAMIC instruction, so the bootstrap can resolve handles later.
     */
    public static void registerPending(String key, String originalMethodName, String dispatchMethodName) {
        PENDING.put(key, new String[]{originalMethodName, dispatchMethodName});
    }

    /**
     * Installs pre-resolved handles for {@code key}. Called by {@code HyperMixins.register}
     * after retransformation for already-loaded target classes.
     */
    public static void install(String key, MethodHandle original, MethodHandle mixin) {
        ORIGINALS.put(key, original);
        MIXINS.put(key, mixin);
        PENDING.remove(key);
        MutableCallSite cs = SITES.computeIfAbsent(key, k -> new MutableCallSite(mixin.type()));
        cs.setTarget(mixin.asType(cs.type()));
    }

    /** Switches the call-site for {@code key} to the original implementation. */
    public static void disable(String key) {
        MethodHandle orig = ORIGINALS.get(key);
        if (orig == null) return;
        MutableCallSite cs = SITES.get(key);
        if (cs != null) cs.setTarget(orig.asType(cs.type()));
    }

    /** Switches the call-site for {@code key} back to the mixin handler. */
    public static void enable(String key) {
        MethodHandle mixin = MIXINS.get(key);
        if (mixin == null) return;
        MutableCallSite cs = SITES.get(key);
        if (cs != null) cs.setTarget(mixin.asType(cs.type()));
    }

    /** Whether the call-site for {@code key} is currently pointing to the mixin handler. */
    public static boolean isActive(String key) {
        MutableCallSite cs = SITES.get(key);
        MethodHandle mixin = MIXINS.get(key);
        return cs != null && mixin != null && cs.getTarget().equals(mixin.asType(cs.type()));
    }

    /**
     * Removes the mixin handler for {@code key} so future calls dispatch to the original
     * implementation. Call-site stays installed (the target class's INVOKEDYNAMIC will keep
     * resolving against this key), but {@link #enable} on the same key becomes a no-op until
     * a new handler is {@link #install installed} or {@link #reinstall reinstalled}.
     */
    public static void uninstall(String key) {
        MIXINS.remove(key);
        PENDING.remove(key);
        MethodHandle orig = ORIGINALS.get(key);
        MutableCallSite cs = SITES.get(key);
        if (cs != null && orig != null) cs.setTarget(orig.asType(cs.type()));
    }

    /**
     * Hot-swaps the mixin handler for {@code key}. Use after redefining the mixin class
     * (e.g., during dev-time code reload). The original handle is preserved.
     */
    public static void reinstall(String key, MethodHandle newMixin) {
        MIXINS.put(key, newMixin);
        MutableCallSite cs = SITES.get(key);
        if (cs != null) cs.setTarget(newMixin.asType(cs.type()));
    }

    /**
     * Clears all registry state. Visible for tests only — production code must never call this.
     */
    public static void clearForTests() {
        SITES.clear();
        ORIGINALS.clear();
        MIXINS.clear();
        PENDING.clear();
    }

    private static void tryLazyInstall(MethodHandles.Lookup lookup, MethodType callSiteType, String key) {
        String[] pending = PENDING.get(key);
        if (pending == null) return;
        Class<?> lookupClass = lookup.lookupClass();
        // Try the virtual shape first (callSiteType has a leading receiver). If that fails, fall
        // back to the static shape — synthetics emitted by MixinTransformer for a static target
        // method carry ACC_STATIC and have no receiver in their MethodType.
        try {
            MethodType virtualType = callSiteType.dropParameterTypes(0, 1);
            MethodHandle origMH = lookup.findVirtual(lookupClass, pending[0], virtualType);
            MethodHandle dispMH = lookup.findVirtual(lookupClass, pending[1], virtualType);
            ORIGINALS.put(key, origMH);
            MIXINS.put(key, dispMH);
            PENDING.remove(key);
            return;
        } catch (NoSuchMethodException virtualMiss) {
            // fall through to static
        } catch (IllegalAccessException e) {
            throw new BootstrapMethodError("Lazy install failed for mixin key: " + key, e);
        }
        try {
            MethodHandle origMH = lookup.findStatic(lookupClass, pending[0], callSiteType);
            MethodHandle dispMH = lookup.findStatic(lookupClass, pending[1], callSiteType);
            ORIGINALS.put(key, origMH);
            MIXINS.put(key, dispMH);
            PENDING.remove(key);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new BootstrapMethodError("Lazy install failed for mixin key: " + key, e);
        }
    }
}
