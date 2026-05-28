package net.echo.hypermixins.api;

import java.lang.annotation.*;

/**
 * Specifies the bytecode call site targeted by a {@link Redirect}.
 * <p>
 * {@code desc} must be the fully qualified invoke signature, e.g.
 * {@code "java/lang/Thread.sleep(J)V"}.
 * The handler method's descriptor must match the substring starting at {@code '('}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface At {
    /** Fully qualified invoke descriptor: {@code "owner.name(argDesc)returnDesc"}. */
    String desc();
    /** Opcode kind of the call site to match. */
    Call call();
    /** Zero-based occurrence index when the same call site appears multiple times. */
    int index() default 0;
}
