package net.echo.hypermixins.annotations;

/**
 * Callback handle passed to {@link Inject} handlers on target methods with a return value.
 * <p>
 * Call {@link #setReturnValue(Object)} to both cancel and override the return value
 * (only effective when {@link Inject#cancellable()} is {@code true}).
 *
 * @param <T> return type of the target method
 * @author xEcho1337
 */
public final class CallbackInfoReturnable<T> {

    private boolean cancelled;
    private T returnValue;
    private final String id;

    private CallbackInfoReturnable(String id) {
        this.id = id;
    }

    /** Factory used by generated transformer code. */
    public static <T> CallbackInfoReturnable<T> of(String id) {
        return new CallbackInfoReturnable<>(id);
    }

    /** Unique identifier for this injection point (method name). */
    public String id() {
        return id;
    }

    /**
     * Cancels execution of the target method and sets the value to return.
     * Only effective when {@link Inject#cancellable()} is {@code true}.
     */
    public void setReturnValue(T value) {
        this.returnValue = value;
        this.cancelled = true;
    }

    /** Returns whether this callback has been cancelled. */
    public boolean isCancelled() {
        return cancelled;
    }

    /** Returns the override return value set via {@link #setReturnValue(Object)}. */
    public T getReturnValue() {
        return returnValue;
    }
}
