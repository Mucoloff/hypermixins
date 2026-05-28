package net.echo.hypermixins.annotations;

/**
 * Mutable reference box used with {@link Share} for exchanging values between injection handlers.
 *
 * @param <T> the type of the wrapped value
 * @author xEcho1337
 */
public final class Ref<T> {

    private T value;

    private Ref(T initial) {
        this.value = initial;
    }

    /** Creates a ref initialised to {@code null}. */
    public static <T> Ref<T> empty() {
        return new Ref<>(null);
    }

    /** Creates a ref initialised to {@code value}. */
    public static <T> Ref<T> of(T value) {
        return new Ref<>(value);
    }

    public T get() {
        return value;
    }

    public void set(T value) {
        this.value = value;
    }
}
