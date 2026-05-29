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
 * <b>Limitation:</b> {@code @Original} trampolines work only for instance target methods.
 * Static targets are supported by {@code @Overwrite} (the transformer adds a per-target
 * static mixin field initialized in {@code <clinit>}), but {@code @Original} on a static
 * target will fail at link time because the generated trampoline assumes an
 * {@code INVOKEVIRTUAL} dispatch. For static targets, write a plain static helper on your
 * mixin or capture the original behaviour directly inside the handler.
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Original {

    /** Simple name of the target method whose original body to call. */
    String value();
}
