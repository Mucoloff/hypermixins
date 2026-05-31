package net.echo.hypermixins.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link Shadow} or {@link Invoker} method as optional. When the target method is not
 * present on the target class at transform time, the trampoline body is replaced with a
 * {@code throw new UnsupportedOperationException("soft target absent: <name>")} so the rest of
 * the mixin still loads and the absence only surfaces when the missing target is actually called.
 *
 * <pre>{@code
 * @Soft @Shadow public native int optionalReader(Object self);
 * @Soft @Invoker public native void optionalDoer(Object self, int arg);
 * }</pre>
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Soft {}
