package net.echo.hypermixins.api;

import java.lang.annotation.*;

/**
 * Replaces a specific method call site inside a target method with a static handler.
 * <p>
 * The annotated method must be {@code static}. Its descriptor must match the invoke
 * signature specified in {@link At#desc()}, starting from the {@code '('}.
 * For virtual/interface calls the first parameter must be the receiver type.
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Redirect {
    /** Simple name of the method in the target class that contains the call site. */
    String method();
    /** Specifies the exact call site to redirect. */
    At at();
}
