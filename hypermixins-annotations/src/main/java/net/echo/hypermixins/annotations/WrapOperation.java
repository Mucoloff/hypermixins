package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Wraps an entire method call site or expression, replacing it with a handler that can
 * inspect or modify the arguments, call the original via {@link Operation}, and modify the result.
 * <p>
 * The annotated (static) handler receives all original arguments plus an {@link Operation}
 * as the last parameter:
 *
 * <pre>{@code
 * @WrapOperation(method = "process", at = @At(desc = "net/example/Util.compute(I)I"))
 * private static int wrapCompute(int input, Operation<Integer> op) throws Throwable {
 *     System.out.println("before compute");
 *     int result = op.call(input);
 *     System.out.println("after compute: " + result);
 *     return result;
 * }
 * }</pre>
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface WrapOperation {

    /** Simple name of the target method containing the operation to wrap. */
    String method();

    /** Specifies the call site or expression to wrap. */
    At at();
}
