package net.echo.hypermixins.annotations;

import java.lang.annotation.*;

/**
 * Declares a shadow reference to a field or method in the target class.
 * <p>
 * Shadow members exist only for compile-time type-checking and IDE navigation.
 * The transformer validates their existence in the target but generates no bytecode for them.
 * <p>
 * Fields annotated {@code @Shadow} must match the name and type of the target field.
 * Methods annotated {@code @Shadow} must match the name and parameter types of the target method.
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface Shadow {

    /**
     * Optional prefix to strip when resolving the target name.
     * E.g. prefix {@code "shadow$"} on field {@code shadow$health} resolves to target field {@code health}.
     */
    String prefix() default "";
}
