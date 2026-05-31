package net.echo.hypermixins.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Constrains an {@code @Inject} site search to a range bounded by two {@code @At} matchers.
 * The handler is only inserted at sites whose instruction index falls between the first match
 * of {@link #from()} (inclusive) and the last match of {@link #to()} (inclusive). Either
 * bound may be left at its default {@code @At} (HEAD with empty {@code desc}) to mean "no
 * bound on this side". Sponge-style semantics.
 *
 * <pre>{@code
 * @Inject(method = "run", at = @At(point = At.Point.INVOKE, desc = "java/util/List.add*"))
 * @Slice(from = @At(point = At.Point.INVOKE, desc = "java/lang/System.currentTimeMillis()J"))
 * public void onAdd(Object self) { ... }
 * }</pre>
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Slice {
    At from() default @At(point = At.Point.HEAD);
    At to() default @At(point = At.Point.HEAD);
}
