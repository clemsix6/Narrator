package io.github.clemsix6.narrator2.forge.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The container of repeated {@link Param}s: the compiler writes it; a module writes {@code @Param} once per parameter. */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface ParamList {
    Param[] value();
}
