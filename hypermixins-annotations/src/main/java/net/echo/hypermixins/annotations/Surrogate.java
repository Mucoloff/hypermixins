package net.echo.hypermixins.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Fallback handler for an {@code @Inject} whose signature stops matching the target after a
 * refactor. The runtime tries the primary handler first; on a capture / slot-resolution
 * failure it walks the surrogate list and retries with each in turn.
 *
 * <p>{@link #value()} optionally names the primary handler method to attach to (handler method
 * name on the mixin class). Empty default: attach to every primary {@code @Inject} on the same
 * target method name in the same mixin class.
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Surrogate {
    String value() default "";
}
