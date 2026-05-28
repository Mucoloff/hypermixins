package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Intercepts and replaces any value-producing bytecode expression inside a target method.
 * <p>
 * More general than {@link ModifyReturnValue} — applies to any opcode that produces a value
 * (INVOKE, GETFIELD, GETSTATIC, LDC, arithmetic, etc.) specified via {@link At}.
 * The handler receives the produced value and returns the replacement.
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ModifyExpressionValue {

    /** Simple name of the target method containing the expression. */
    String method();

    /** Specifies the expression site to intercept. */
    At at();
}
