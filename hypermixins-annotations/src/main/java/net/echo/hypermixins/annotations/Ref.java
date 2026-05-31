package net.echo.hypermixins.annotations;

import java.util.function.Consumer;
import java.util.function.Supplier;

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

    public boolean isPresent() {
        return value != null;
    }

    public boolean isEmpty() {
        return value == null;
    }

    public T orElse(T other) {
        return value != null ? value : other;
    }

    public T orElseGet(Supplier<T> other) {
        return orElse(other.get());
    }

    public void ifPresent(Consumer<? super T> consumer) {
        if (value != null) {
            consumer.accept(value);
        }
    }

    public void ifPresentOrElse(Consumer<? super T> consumer, Runnable defaultAction) {
        if (value != null) consumer.accept(value);
        else defaultAction.run();
    }
}
