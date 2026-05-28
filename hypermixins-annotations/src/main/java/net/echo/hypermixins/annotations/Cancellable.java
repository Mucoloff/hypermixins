package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Marker annotation equivalent to setting {@link Inject#cancellable()} to {@code true}.
 * Can be placed on the method alongside {@link Inject} for readability.
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Cancellable {}
