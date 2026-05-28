package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Modifies all arguments of a method call site in the target method at once.
 * <p>
 * The annotated (static) handler receives an {@link Args} wrapper giving typed
 * access to each argument by position; modifications are applied in-place.
 *
 * <pre>{@code
 * @ModifyArgs(method = "spawn", at = @At(desc = "net/example/World.createEntity(IIF)V"))
 * private static void adjustSpawnArgs(Args args) {
 *     args.set(2, (float) args.get(2) * 2.0f);
 * }
 * }</pre>
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ModifyArgs {

    /** Simple name of the target method containing the call site. */
    String method();

    /** Specifies the call site to intercept. */
    At at();
}
