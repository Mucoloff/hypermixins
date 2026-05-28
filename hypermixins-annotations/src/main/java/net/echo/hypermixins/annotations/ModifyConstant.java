package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Modifies a constant ({@code LDC} / {@code BIPUSH} / {@code SIPUSH} / {@code ICONST_n} etc.)
 * loaded inside a target method.
 * <p>
 * The annotated (static) handler receives the original constant value and returns the replacement.
 * Use {@link Constant} to specify which constant to match.
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ModifyConstant {

    /** Simple name of the target method containing the constant. */
    String method();

    /** Descriptor of the constant to intercept. */
    Constant constant();

    /** Zero-based occurrence index if the same constant appears multiple times. */
    int index() default 0;

    /**
     * Describes a constant value to match in the bytecode.
     * Exactly one typed value member should be set; others are ignored.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface Constant {
        int intValue() default Integer.MIN_VALUE;
        long longValue() default Long.MIN_VALUE;
        float floatValue() default Float.NaN;
        double doubleValue() default Double.NaN;
        String stringValue() default "";
        Class<?> classValue() default Void.class;
    }
}
