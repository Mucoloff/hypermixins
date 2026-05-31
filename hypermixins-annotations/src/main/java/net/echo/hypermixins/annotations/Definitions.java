package net.echo.hypermixins.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Repeatable container for {@link Definition}. Users normally write multiple
 * {@code @Definition} entries directly; the compiler folds them into this container
 * automatically.
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Definitions {
    Definition[] value();
}
