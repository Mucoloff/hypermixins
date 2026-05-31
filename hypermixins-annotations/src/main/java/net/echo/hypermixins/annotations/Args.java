package net.echo.hypermixins.annotations;

import java.util.List;
import java.util.Objects;

/**
 * Mutable argument wrapper passed to {@link ModifyArgs} handlers.
 * <p>
 * Provides typed get/set access to a call-site's argument list.
 * The backing array is the live argument list; modifications are applied before the call.
 *
 * @author xEcho1337
 */
public final class Args {

    private final Object[] args;

    private Args(Object[] args) {
        this.args = Objects.requireNonNull(args, "args");
    }

    /** Factory used by generated transformer code. */
    public static Args of(Object... args) {
        return new Args(args);
    }

    /** Returns the argument at {@code index} cast to {@code T}. */
    public <T> T get(int index) {
        //noinspection unchecked
        return (T) args[index];
    }

    /** Replaces the argument at {@code index} with {@code value}. */
    public void set(int index, Object value) {
        args[index] = value;
    }

    /** Number of arguments. */
    public int size() {
        return args.length;
    }

    /** Returns an unmodifiable view of the current argument values. */
    public List<Object> values() {
        return List.of(args);
    }

    /** Returns the raw backing array (used by generated transformer code). */
    public Object[] raw() {
        return args;
    }
}
