package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Synthesizes a getter or setter for a private field in the target class.
 * <p>
 * Naming convention (auto-resolved if {@link #value()} is empty):
 * <ul>
 *   <li>{@code getFoo()} / {@code isFoo()} → getter for field {@code foo}</li>
 *   <li>{@code setFoo(T)} → setter for field {@code foo}</li>
 * </ul>
 * Declare the method {@code native} or with a stub body — the transformer replaces it.
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Accessor {

    /**
     * Name of the target field. When empty, the field name is derived from the method name
     * by stripping the {@code get}/{@code set}/{@code is} prefix and lowercasing the first letter.
     */
    String value() default "";
}
