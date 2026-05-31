package net.echo.hypermixins.agent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Shared validation prologue used by {@link ReflectionCollectors}. Pulls the
 * repeated {@code throw new IllegalArgumentException(...)} scaffolding into one place so
 * every collector reports the same error shape and per-annotation methods stay focused on
 * their bespoke checks.
 */
final class ReflectionCollectorChecks {

    private ReflectionCollectorChecks() {}

    /** Reject blank {@code value} (e.g. {@code @ModifyXxx#method()} / {@code @Shadow#value()}). */
    static void requireNonEmpty(String value, String ann, String field, Method m) {
        if (value.isEmpty())
            throw new IllegalArgumentException(ann + "#" + field + "() must not be empty on " + m);
    }

    /** Reject blank {@code @At#desc()} for annotations that always require one. */
    static void requireAtDescNonEmpty(String desc, String ann, Method m) {
        if (desc.isEmpty())
            throw new IllegalArgumentException("@At#desc() must not be empty on " + ann + " " + m);
    }

    /** Annotation requires the handler to be {@code static}. */
    static void requireStatic(Method m, String ann) {
        if (!Modifier.isStatic(m.getModifiers()))
            throw new IllegalArgumentException(ann + " must be static: " + m);
    }

    /** Annotation requires the handler to be a non-static (instance) method. */
    static void requireNonStatic(Method m, String ann) {
        if (Modifier.isStatic(m.getModifiers()))
            throw new IllegalArgumentException(ann + " must not be static: " + m);
    }
}
