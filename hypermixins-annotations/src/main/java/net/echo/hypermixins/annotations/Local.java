package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Captures a local variable from the target method's frame and injects it as
 * an additional parameter into an {@link Inject} or {@link WrapOperation} handler.
 * <p>
 * Resolution strategy (first applicable wins):
 * <ol>
 *   <li>{@link #index()} — absolute local variable slot index.</li>
 *   <li>{@link #ordinal()} — zero-based ordinal among locals of matching type at the injection point.</li>
 *   <li>Type alone — when only one local of that type is in scope.</li>
 * </ol>
 * Annotate the parameter in the handler method:
 * <pre>{@code
 * @Inject(method = "process", at = @At(point = At.Point.INVOKE, desc = "..."))
 * private void onProcess(@Local int count, CallbackInfo ci) { }
 * }</pre>
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Local {

    /** Absolute local variable slot index. {@code -1} = auto-resolve by ordinal/type. */
    int index() default -1;

    /** Zero-based ordinal among locals of matching type at the injection point. {@code -1} = auto. */
    int ordinal() default -1;

    /**
     * When {@code true}, the local variable is also writeable (requires the handler to accept
     * the parameter as a single-element array so the transformer can write back the value).
     */
    boolean argsOnly() default false;
}
