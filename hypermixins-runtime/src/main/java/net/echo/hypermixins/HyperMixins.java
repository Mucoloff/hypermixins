package net.echo.hypermixins;

import net.echo.hypermixins.agent.MixinMapping;
import net.echo.hypermixins.agent.MixinTransformer;
import net.echo.hypermixins.api.MixinHandle;
import net.echo.hypermixins.registry.MixinRegistry;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Entry point for registering and applying HyperMixins through the Java Instrumentation API.
 * <p>
 * Returns a {@link MixinHandle} per-registration enabling atomic enable/disable of mixin
 * dispatch without bytecode retransformation (via {@link MixinRegistry} + {@code INVOKEDYNAMIC}).
 *
 * @author xEcho1337
 */
public final class HyperMixins {

    private HyperMixins() {}

    /**
     * Registers one or more mixin classes and returns a {@link MixinHandle} for lifecycle control.
     *
     * @param inst             the {@link Instrumentation} from the agent
     * @param mixinClasses     mixin classes annotated with {@code @Mixin}
     * @param preloadedTargets already-loaded target classes to retransform immediately
     * @return a handle for enable/disable/unregister lifecycle control
     * @throws MixinRegistrationException if registration or retransformation fails
     */
    public static MixinHandle register(
        Instrumentation inst,
        Class<?>[] mixinClasses,
        Class<?>... preloadedTargets
    ) throws MixinRegistrationException {
        try {
            List<MixinMapping> mappings = new ArrayList<>();
            for (Class<?> mixin : mixinClasses) {
                mappings.add(new MixinMapping(mixin));
            }

            MixinTransformer transformer = new MixinTransformer(mappings);
            inst.addTransformer(transformer, true);
            // Transforms @Original trampolines in mixin classes
            inst.retransformClasses(mixinClasses);

            // Transform target classes that are already loaded
            if (preloadedTargets.length > 0) {
                inst.retransformClasses(preloadedTargets);
                // Install MethodHandles for each now-transformed target
                for (MixinMapping mapping : mappings) {
                    installHandles(mapping, preloadedTargets);
                }
            }

            return MixinHandle.of(inst, transformer, transformer.registeredKeys());
        } catch (UnmodifiableClassException | IllegalArgumentException e) {
            throw new MixinRegistrationException("Failed to register mixins", e);
        }
    }

    /** Convenience overload — no preloaded targets. */
    public static MixinHandle register(Instrumentation inst, Class<?>... mixinClasses)
        throws MixinRegistrationException {
        return register(inst, mixinClasses, new Class<?>[0]);
    }

    /**
     * Installs original and dispatch {@link MethodHandle}s into {@link MixinRegistry} for all
     * overwrite entries of {@code mapping} whose target class is among {@code loadedTargets}.
     * <p>
     * This is called eagerly for preloaded targets. Classes loaded later are handled by
     * the lazy bootstrap in {@link MixinRegistry#bootstrap}.
     */
    private static void installHandles(MixinMapping mapping, Class<?>[] loadedTargets)
        throws MixinRegistrationException {
        String targetInternal = mapping.getTargetClass().replace('.', '/');

        Class<?> targetCls = null;
        for (Class<?> c : loadedTargets) {
            if (c.getName().equals(mapping.getTargetClass())) { targetCls = c; break; }
        }
        if (targetCls == null) return;

        MethodHandles.Lookup lookup;
        try {
            lookup = MethodHandles.privateLookupIn(targetCls, MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            throw new MixinRegistrationException(
                "Cannot acquire private lookup for " + mapping.getTargetClass(), e);
        }

        for (Map.Entry<String, java.lang.reflect.Method> entry : mapping.getOverwrites().entrySet()) {
            String targetMethodKey = entry.getKey(); // "methodName(params...)ret"
            int parenIdx = targetMethodKey.indexOf('(');
            String methodName = targetMethodKey.substring(0, parenIdx);
            String methodDesc = targetMethodKey.substring(parenIdx);

            String key          = targetInternal + "#" + methodName + methodDesc;
            String originalName = MixinTransformer.mangledName(methodName, methodDesc);
            String dispatchName = MixinTransformer.dispatchName(methodName, methodDesc);

            try {
                MethodType methodType = MethodType.fromMethodDescriptorString(
                    methodDesc, targetCls.getClassLoader());
                MethodHandle origMH = lookup.findVirtual(targetCls, originalName, methodType);
                MethodHandle dispMH = lookup.findVirtual(targetCls, dispatchName, methodType);
                MixinRegistry.install(key, origMH, dispMH);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new MixinRegistrationException(
                    "Failed to install MethodHandles for " + key, e);
            }
        }
    }

    /** Typed exception wrapping any failure during mixin registration. */
    public static final class MixinRegistrationException extends Exception {
        public MixinRegistrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
