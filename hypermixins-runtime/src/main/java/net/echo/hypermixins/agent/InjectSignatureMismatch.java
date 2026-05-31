package net.echo.hypermixins.agent;

/**
 * Marker thrown by {@link CaptureEmitter} / {@link InjectLocalResolver} when an {@code @Inject}
 * handler signature or {@code @Local} binding cannot be resolved against the current target.
 * Distinct from plain {@link IllegalStateException} so {@link InjectPass} can selectively
 * fall back to {@code @Surrogate} handlers without swallowing unrelated transform errors.
 */
final class InjectSignatureMismatch extends IllegalStateException {
    InjectSignatureMismatch(String msg) { super(msg); }
}
