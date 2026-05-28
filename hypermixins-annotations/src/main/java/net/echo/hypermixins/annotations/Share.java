package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Declares a shared variable between multiple injection handlers in the same mixin.
 * <p>
 * The share is keyed by {@link #value()} and the parameter type. Handlers that declare a
 * {@code @Share} parameter with the same key and type share the same storage slot across
 * injection points within a single target method invocation.
 *
 * <pre>{@code
 * @Inject(method = "tick", at = @At(point = At.Point.HEAD))
 * private void onTickHead(@Share("timer") Ref<Integer> timer, CallbackInfo ci) {
 *     timer.set(0);
 * }
 *
 * @Inject(method = "tick", at = @At(point = At.Point.RETURN))
 * private void onTickReturn(@Share("timer") Ref<Integer> timer, CallbackInfo ci) {
 *     System.out.println("tick took: " + timer.get());
 * }
 * }</pre>
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Share {

    /** Unique key identifying the shared variable within the mixin for the target method. */
    String value();
}
