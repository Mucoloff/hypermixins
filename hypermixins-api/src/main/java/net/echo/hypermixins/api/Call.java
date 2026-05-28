package net.echo.hypermixins.api;

/**
 * Bytecode invoke opcode for a {@link Redirect} call site.
 *
 * @author xEcho1337
 */
public enum Call {
    /** {@code INVOKEVIRTUAL} — instance method on a class. Also matches {@code INVOKEINTERFACE}. */
    INVOKEVIRTUAL,
    /** {@code INVOKESTATIC} — static method. */
    INVOKESTATIC,
    /** {@code INVOKEINTERFACE} — interface method (strict match). */
    INVOKEINTERFACE,
    /** {@code INVOKESPECIAL} — constructor, super, or private call. */
    INVOKESPECIAL
}
