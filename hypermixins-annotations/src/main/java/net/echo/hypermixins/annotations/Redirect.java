package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Redirects a specific method call site inside a target method to a static handler.
 * <p>
 * The annotated method must be {@code static}. Its descriptor must match the invoke
 * signature specified in {@link At#desc()}, starting from the {@code '('}.
 * For virtual/interface calls, the first parameter must be the receiver type.
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Redirect {

    /** Simple name of the method in the target class that contains the call site to redirect. */
    String method();

    /** Specifies the exact call site to intercept. */
    At at();
}
