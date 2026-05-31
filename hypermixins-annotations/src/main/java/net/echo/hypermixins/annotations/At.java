package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Specifies a bytecode injection or interception point.
 * <p>
 * When used inside {@link Redirect}: {@code desc} is the fully qualified invoke signature
 * ({@code "owner/Class.methodName(argDesc)retDesc"}).
 * <p>
 * When used inside {@link Inject}: {@link #point()} selects HEAD/TAIL/RETURN/INVOKE.
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface At {

    /**
     * Fully qualified invoke/field descriptor for INVOKE/FIELD points.
     * Format: {@code "owner/Class.memberName(argDesc)retDesc"} for invokes,
     * {@code "owner/Class.fieldName:Ldesc;"} for fields.
     */
    String desc() default "";

    /** Opcode kind of the bytecode site to match. */
    Call call() default Call.INVOKEVIRTUAL;

    /** Zero-based occurrence index when the same site appears multiple times. */
    int index() default 0;

    /** Injection point for {@link Inject}: position within the method body. */
    Point point() default Point.INVOKE;

    /** Anchor: insert {@link Shift#BEFORE before}, {@link Shift#AFTER after}, or {@link Shift#BY} an instruction offset away from the matched site. */
    Shift shift() default Shift.BEFORE;

    /**
     * Signed instruction-offset applied when {@link #shift()} is {@link Shift#BY}. Negative
     * values pull the insertion point earlier in the method body, positive push it later.
     * Ignored for {@link Shift#BEFORE} / {@link Shift#AFTER}.
     */
    int by() default 0;

    /** Anchor relative to the matched instruction. */
    enum Shift { BEFORE, AFTER, BY }

    /** Injection point positions for {@link Inject}. */
    enum Point {
        /** Before the first instruction of the method. */
        HEAD,
        /** Before every {@code RETURN} opcode in the method. */
        RETURN,
        /** After the last instruction (before method end). Equivalent to RETURN for void methods. */
        TAIL,
        /** At a specific INVOKE site (requires {@link #desc()} and optionally {@link #index()}). */
        INVOKE,
        /** At a specific FIELD access site (requires {@link #desc()}). */
        FIELD,
        /** At a specific constant load (requires desc like {@code "I:42"} or {@code "Ljava/lang/String;:hello"}). */
        CONSTANT,
        /** At a specific conditional jump (requires {@link #index()}). */
        JUMP,
        /** At a {@code NEW} object allocation (requires {@link #desc()} as {@code "owner/Class"}). */
        NEW
    }
}
