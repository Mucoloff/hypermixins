package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Synthesizes an invoker for a private or inaccessible method in the target class.
 * <p>
 * Naming convention (auto-resolved if {@link #value()} is empty):
 * <ul>
 *   <li>{@code invokeBar(args)} / {@code callBar(args)} → calls {@code bar(args)} on the target.</li>
 * </ul>
 * Declare the method {@code native} — the transformer supplies the body.
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Invoker {

    /**
     * Name of the target method to invoke. When empty, derived by stripping
     * the {@code invoke}/{@code call} prefix from the annotated method name.
     */
    String value() default "";
}
