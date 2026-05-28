package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Wraps the entire target method body.
 * <p>
 * The annotated handler receives the original method arguments plus an {@link Operation}
 * representing the original body. The handler may invoke the original body zero or more times.
 *
 * <pre>{@code
 * @WrapMethod("tick")
 * private void wrapTick(Operation<Void> original) throws Throwable {
 *     if (!paused) original.call();
 * }
 * }</pre>
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface WrapMethod {

    /** Simple name of the target method to wrap. */
    String value();
}
