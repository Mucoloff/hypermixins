package net.echo.hypermixins.api;

import java.lang.annotation.*;

/**
 * Generates a trampoline stub that calls the saved original implementation
 * of a method overwritten via {@link Overwrite}.
 * <p>
 * The annotated method must declare {@code Object self} as its first parameter.
 * Declare it {@code native} to suppress the compiler body requirement:
 * <pre>{@code
 * @Original("getPlayers")
 * public native List<Player> getPlayersOrig(Object self);
 * }</pre>
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Original {
    /** Name of the target method whose original body to call. */
    String value();
}
