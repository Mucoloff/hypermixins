package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Conditionally suppresses a method call site based on a boolean predicate.
 * <p>
 * The annotated (static) handler receives the same arguments as the target call site and
 * returns {@code true} to allow the call or {@code false} to skip it. For call sites with
 * a return value, the default zero/null value is used when suppressed.
 *
 * <pre>{@code
 * @WrapWithCondition(method = "render", at = @At(desc = "net/example/Renderer.draw(Lnet/example/Shape;)V"))
 * private static boolean shouldDraw(Shape shape) {
 *     return shape.isVisible();
 * }
 * }</pre>
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface WrapWithCondition {

    /** Simple name of the target method containing the call site. */
    String method();

    /** Specifies the call site to conditionally suppress. */
    At at();
}
