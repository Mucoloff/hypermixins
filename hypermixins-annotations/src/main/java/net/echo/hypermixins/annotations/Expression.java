package net.echo.hypermixins.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * DSL site selector for {@code @Inject(at = @At(point = EXPRESSION))}. The string references
 * {@link Definition} ids declared on the same handler and selects every target instruction
 * that matches the expression AST.
 *
 * <p>v1 grammar (single instruction):
 * <pre>
 *   expression := call | fieldRef ;
 *   call       := IDENT "(" args? ")" ;
 *   args       := arg ("," arg)* ;
 *   arg        := "?" ;
 *   fieldRef   := IDENT ;
 * </pre>
 *
 * Identifiers must match a {@link Definition} id declared on the handler. {@code ?} is an
 * unbound placeholder (capture-binding is deferred).
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Expression {
    String value();
}
