package net.echo.hypermixins.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names a method or field signature that can be referenced from an {@link Expression} DSL by
 * its {@link #id()}. Exactly one of {@link #method()} or {@link #field()} must be non-empty.
 *
 * <p>Method form: {@code "owner/Class.name(argDesc)retDesc"} — matches a JVM invoke site.
 * <p>Field  form: {@code "owner/Class.name:Ldesc;"} — matches a JVM field access.
 *
 * <p>Multiple {@code @Definition}s can be declared on the same handler via the
 * {@link Definitions} container — they share a flat {@code id → signature} namespace scoped
 * to the handler method.
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(Definitions.class)
public @interface Definition {
    String id();
    String method() default "";
    String field() default "";
}
