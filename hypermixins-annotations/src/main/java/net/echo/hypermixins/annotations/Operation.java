package net.echo.hypermixins.annotations;

/**
 * Functional interface representing the original operation wrapped by {@link WrapOperation}.
 * <p>
 * Call {@link #call(Object...)} to invoke the original bytecode operation with the given arguments.
 *
 * @param <T> return type of the wrapped operation
 * @author xEcho1337
 */
@FunctionalInterface
public interface Operation<T> {

    /**
     * Invokes the original wrapped operation.
     *
     * @param args the arguments to pass (for INVOKEVIRTUAL: receiver first, then method args)
     * @return the result of the original operation
     * @throws Throwable any throwable the original operation may throw
     */
    T call(Object... args) throws Throwable;
}
