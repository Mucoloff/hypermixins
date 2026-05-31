package net.echo.hypermixins.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker on an {@code @Inject} handler parameter (typically paired with {@code @Local}) that
 * relaxes the strict type-equality match the resolver does to an assignability check. Useful
 * when the target declares a concrete type (e.g. {@code ArrayList<String>}) but the handler
 * wants to bind it through a wider reference (e.g. {@code List<String>}). Only meaningful on
 * reference parameters — primitives ignore the marker.
 *
 * <pre>{@code
 * @Inject(method = "run", at = @At(point = Point.INVOKE, desc = "..."))
 * public void onSite(Object self, @Local @Coerce List<String> wider) { ... }
 * }</pre>
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Coerce {}
