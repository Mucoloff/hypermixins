package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Intercepts and replaces the return value of a method call site inside the target method.
 * <p>
 * The annotated (static) handler receives the original return value and must return
 * the replacement. The handler's return type must match the call-site's return type.
 *
 * <pre>{@code
 * @ModifyReturnValue(method = "update", at = @At(desc = "net/example/Dao.load()Lnet/example/Data;"))
 * private static Data wrapData(Data original) {
 *     return new DecoratedData(original);
 * }
 * }</pre>
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ModifyReturnValue {

    /** Simple name of the target method containing the call site. */
    String method();

    /** Specifies the call site whose return value to intercept. */
    At at();
}
