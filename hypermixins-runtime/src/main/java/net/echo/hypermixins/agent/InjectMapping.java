package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.At;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Mapping for an {@code @Inject} handler. Target method is matched by simple name only
 * (HEAD/RETURN/TAIL points do not need a descriptor — they attach to all methods named {@link #targetMethod()}).
 *
 * <p>{@code surrogates}: optional fallback handler list. If the primary {@code handler} fails
 * signature / capture / slot resolution at transform time, {@code InjectPass} retries with each
 * surrogate in declared order.
 */
public record InjectMapping(
    String targetMethod,
    At.Point point,
    int index,
    String atDesc,
    boolean cancellable,
    boolean returnable,
    Method handler,
    List<Method> surrogates
) {
    public InjectMapping(String targetMethod, At.Point point, int index, String atDesc,
                         boolean cancellable, boolean returnable, Method handler) {
        this(targetMethod, point, index, atDesc, cancellable, returnable, handler, List.of());
    }
}
