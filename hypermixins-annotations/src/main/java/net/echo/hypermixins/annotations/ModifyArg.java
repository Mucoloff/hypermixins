package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Modifies a single argument of a method call site in the target method.
 * <p>
 * The annotated (static) handler receives the original argument value and must return
 * the replacement value. The argument index is zero-based among the arguments of the
 * call site (not counting the receiver for virtual calls).
 *
 * <pre>{@code
 * @ModifyArg(method = "onMessage", at = @At(desc = "net/example/Chat.send(Ljava/lang/String;)V"), index = 0)
 * private static String modifyMessage(String original) {
 *     return original.toUpperCase();
 * }
 * }</pre>
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ModifyArg {

    /** Simple name of the target method containing the call site. */
    String method();

    /** Specifies the call site to intercept. */
    At at();

    /** Zero-based index of the argument to replace. */
    int index() default 0;
}
