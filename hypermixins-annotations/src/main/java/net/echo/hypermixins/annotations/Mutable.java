package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Removes the {@code final} modifier from a {@link Shadow} + {@link Final} field,
 * allowing the mixin to write to it.
 * <p>
 * Use with {@link Shadow} + {@link Final} on the same field:
 * <pre>{@code
 * @Mutable @Shadow @Final private int frozenValue;
 * }</pre>
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Mutable {}
