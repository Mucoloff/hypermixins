package net.echo.hypermixins;

import net.echo.hypermixins.agent.MixinMapping;
import net.echo.hypermixins.agent.MixinTransformer;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.*;

/**
 * Entry point for registering and applying HyperMixins through the Java Instrumentation API.
 * <p>
 * Intended for use inside a Java agent's {@code premain} method.
 * Installs a {@link MixinTransformer} and triggers retransformation of the mixin and target classes.
 *
 * @author xEcho1337
 */
public class HyperMixins {

    private HyperMixins() {}

    /**
     * Registers mixin classes. If {@code preloadedTargets} is provided, retransforms those
     * target classes immediately (needed when targets are already loaded before the agent attaches).
     *
     * @param inst             the Instrumentation from the agent
     * @param mixinClasses     mixin classes annotated with {@code @Mixin}
     * @param preloadedTargets already-loaded target classes to force retransform
     * @throws MixinRegistrationException if registration or retransformation fails
     */
    public static void register(
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
            inst.retransformClasses(mixinClasses);

            if (preloadedTargets.length > 0) {
                inst.retransformClasses(preloadedTargets);
            }
        } catch (UnmodifiableClassException | IllegalArgumentException e) {
            throw new MixinRegistrationException("Failed to register mixins", e);
        }
    }

    /**
     * Convenience overload — no preloaded targets.
     *
     * @param inst         the Instrumentation from the agent
     * @param mixinClasses mixin classes annotated with {@code @Mixin}
     * @throws MixinRegistrationException if registration or retransformation fails
     */
    public static void register(Instrumentation inst, Class<?>... mixinClasses) throws MixinRegistrationException {
        register(inst, mixinClasses, new Class<?>[0]);
    }

    /** Typed exception wrapping any failure during mixin registration. */
    public static final class MixinRegistrationException extends Exception {
        public MixinRegistrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
