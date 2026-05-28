package net.echo.hypermixins.annotations;

/**
 * Callback handle passed to {@link Inject} handlers on void-returning target methods.
 * <p>
 * Call {@link #cancel()} to stop execution of the target method (only when
 * {@link Inject#cancellable()} is {@code true}).
 *
 * @author xEcho1337
 */
public final class CallbackInfo {

    private boolean cancelled;
    private final String id;

    private CallbackInfo(String id) {
        this.id = id;
    }

    /** Factory used by generated transformer code. */
    public static CallbackInfo of(String id) {
        return new CallbackInfo(id);
    }

    /** Unique identifier for this injection point (method name). */
    public String id() {
        return id;
    }

    /** Cancels execution of the target method. Only effective when {@link Inject#cancellable()} is true. */
    public void cancel() {
        this.cancelled = true;
    }

    /** Returns whether this callback has been cancelled. */
    public boolean isCancelled() {
        return cancelled;
    }
}
