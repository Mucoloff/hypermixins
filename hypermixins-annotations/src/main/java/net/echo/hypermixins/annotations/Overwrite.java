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
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Overwrite {

    /** Simple name of the target method to replace. */
    String value();
}
