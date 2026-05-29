package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Replaces the body of a method in the target class while preserving the original
 * implementation accessible via {@link Original}.
 * <p>
 * The annotated method must:
 * <ul>
 *   <li>Not be {@code static}.</li>
 *   <li>Declare {@code Object self} as its first parameter (use explicit cast inside the body).</li>
 *   <li>Not reference the target class type in its parameter list directly.</li>
 * </ul>
 *
 * <p>
 * <b>Recursion warning:</b> a handler that calls the overwritten method back on {@code self}
 * (e.g., {@code ((Target) self).foo()}) re-enters its {@code INVOKEDYNAMIC} call-site and
 * loops indefinitely until a {@link StackOverflowError}. To reach the un-mixed body of the
 * same method, declare an {@code @Original}-annotated native trampoline on the mixin and
 * invoke it instead.
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Overwrite {

    /** Simple name of the target method to replace. */
    String value();
}
