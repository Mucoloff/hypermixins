package net.echo.hypermixins.annotations;

/**
 * Bytecode instruction opcode for an {@link At} injection or interception site.
 *
 * @author xEcho1337
 */
public enum Call {
    /** {@code INVOKEVIRTUAL} — instance method on a class. Also matches {@code INVOKEINTERFACE} in INVOKEVIRTUAL mode. */
    INVOKEVIRTUAL,
    /** {@code INVOKESTATIC} — static method. */
    INVOKESTATIC,
    /** {@code INVOKEINTERFACE} — interface method (strict opcode match). */
    INVOKEINTERFACE,
    /** {@code INVOKESPECIAL} — constructor, superclass, or private method call. */
    INVOKESPECIAL,
    /** {@code NEW} — object allocation. */
    NEW,
    /** {@code GETFIELD} — instance field read. */
    GETFIELD,
    /** {@code PUTFIELD} — instance field write. */
    PUTFIELD,
    /** {@code GETSTATIC} — static field read. */
    GETSTATIC,
    /** {@code PUTSTATIC} — static field write. */
    PUTSTATIC
}
