package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Injects a callback into a target method at a specified point.
 * <p>
 * The annotated method receives a {@link CallbackInfo} (or {@link CallbackInfoReturnable})
 * as its last parameter. When {@link #cancellable()} is {@code true}, calling
 * {@link CallbackInfo#cancel()} short-circuits the target method.
 *
 * <pre>{@code
 * @Inject(method = "tick", at = @At(point = At.Point.HEAD), cancellable = true)
 * private void onTick(CallbackInfo ci) {
 *     if (shouldSkip) ci.cancel();
 * }
 * }</pre>
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Inject {

    /** Simple name of the target method to inject into. */
    String method();

    /** Injection point specification. */
    At at();

    /** When {@code true}, the callback may cancel method execution via {@link CallbackInfo#cancel()}. */
    boolean cancellable() default false;
}
