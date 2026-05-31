package net.echo.hypermixins.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Adds the listed interfaces to the {@code @Mixin} target class at transform time. The mixin
 * is expected to satisfy each interface via its own {@code @Overwrite} / @{@code @Original}
 * methods (or via existing target methods that already match the interface contract).
 *
 * <pre>{@code
 * @Mixin("net.example.Target")
 * @Implements({ java.lang.Runnable.class })
 * public class TargetMixin {
 *     @Overwrite("run")
 *     public void run(Object self) { ... }
 * }
 * }</pre>
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Implements {
    Class<?>[] value();
}
