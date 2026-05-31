package net.echo.hypermixins.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a static helper method on the mixin class as a copy-to-target. At transform time the
 * method bytecode is duplicated into the target class under a mangled name
 * ({@code __unique$<mixin-flat>$<name>$<hash>}) so calls to it from other rewritten mixin
 * bodies resolve against the merged copy. Restricted to static methods for now — instance
 * {@code @Unique} merging interacts with the dispatch invariant and is deferred.
 *
 * @author xEcho1337
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Unique {}
