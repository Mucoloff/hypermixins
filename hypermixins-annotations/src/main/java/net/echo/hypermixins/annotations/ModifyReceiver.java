package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Replaces the receiver object of a virtual method call site inside the target method.
 * <p>
 * The annotated (static) handler receives the original receiver and returns the replacement.
 * The handler's parameter and return type must match the call-site's owner type.
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ModifyReceiver {

    /** Simple name of the target method containing the call site. */
    String method();

    /** Specifies the call site whose receiver to replace. */
    At at();
}
