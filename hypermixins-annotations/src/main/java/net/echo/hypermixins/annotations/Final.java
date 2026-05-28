package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Marks a {@link Shadow} field as {@code final} in the target class.
 * The transformer will verify the target field is indeed final; used to
 * expose it for write via {@link Mutable}.
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Final {}
