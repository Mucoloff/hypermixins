package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.At;

import java.lang.reflect.Method;

/**
 * Mapping for an {@code @Inject} handler. Target method is matched by simple name only
 * (HEAD/RETURN/TAIL points do not need a descriptor — they attach to all methods named {@link #targetMethod()}).
 */
public record InjectMapping(
    String targetMethod,
    At.Point point,
    int index,
    String atDesc,
    boolean cancellable,
    boolean returnable,
    Method handler
) {}
