package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Forwards a {@code native} mixin method to a method on the target class.
 * <p>
 * Use to call into target methods other than the one being overwritten — analogous to
 * Sponge Mixin's {@code @Shadow} for methods.
 *
 * <h2>Methods</h2>
 * The annotated method must:
 * <ul>
 *   <li>Be declared {@code native} on the mixin (no body).</li>
 *   <li>Take {@code Object self} as its first parameter.</li>
 *   <li>Declare remaining parameters that exactly match the target method's signature.</li>
 *   <li>Return whatever the target returns.</li>
 * </ul>
 *
 * <pre>{@code
 * @Mixin("com.example.World")
 * public class WorldMixin {
 *
 *     @Shadow("isLoaded")
 *     public native boolean isLoaded(Object self);
 *
 *     @Overwrite("tick")
 *     public void tick(Object self) {
 *         if (!isLoaded(self)) return;
 *         ...
 *     }
 * }
 * }</pre>
 *
 * <h2>Fields</h2>
 * Field-level {@code @Shadow} is reserved for IDE / compile-time type-checking only; the
 * transformer does not rewrite field accesses (v1 limitation).
 *
 * <h2>Limitations</h2>
 * v1 supports method targets reachable via {@code INVOKEVIRTUAL} from a synthetic helper on
 * the target class (public, protected, or package-private). Private target methods and field
 * forwarding are out of scope.
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface Shadow {

    /** Simple name of the target method to forward to. Required when applied to a method. */
    String value() default "";

    /**
     * Optional prefix to strip when resolving the target name (used by IDE-only field shadows).
     * Example: prefix {@code "shadow$"} on field {@code shadow$health} resolves to target field
     * {@code health}. Methods should prefer the explicit {@link #value()} form.
     */
    String prefix() default "";
}
