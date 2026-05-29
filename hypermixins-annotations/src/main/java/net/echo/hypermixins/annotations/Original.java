package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Generates a trampoline stub that delegates to the saved original implementation
 * of a method overwritten via {@link Overwrite}.
 * <p>
 * The annotated method must:
 * <ul>
 *   <li>Declare {@code Object self} as its first parameter.</li>
 *   <li>Declare remaining parameters matching the original target method signature.</li>
 * </ul>
 * Declare the method {@code native} to suppress the compiler body requirement:
 * <pre>{@code
 * @Original("getPlayers")
 * public native List<Player> getPlayersOrig(Object self);
 * }</pre>
 *
 * <p>
 * <b>Limitation:</b> only instance target methods are supported. Pointing {@code @Original}
 * at a static target method will fail at class link time (the generated trampoline assumes
 * an {@code INVOKEVIRTUAL} dispatch with {@code self} on the stack). For static targets,
 * write a plain static helper on your mixin and call it from your {@code @Overwrite} handler.
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Original {

    /** Simple name of the target method whose original body to call. */
    String value();
}
