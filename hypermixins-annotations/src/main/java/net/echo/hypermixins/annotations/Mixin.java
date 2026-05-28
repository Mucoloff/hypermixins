package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Declares a class as a mixin targeting an existing class for bytecode transformation.
 * <p>
 * Methods inside the mixin class may be annotated with {@link Overwrite}, {@link Original},
 * {@link Redirect}, {@link Inject}, and related annotations to modify the target's behaviour.
 * <p>
 * The annotated class must have a public no-arg constructor {@code ()V}.
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mixin {

    /**
     * Fully qualified binary name of the target class (e.g. {@code "com.example.MyClass"}).
     * Inner classes use {@code $} notation: {@code "com.example.Outer$Inner"}.
     */
    String value();
}
