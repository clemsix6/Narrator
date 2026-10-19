package io.github.clemsix6.narrator2.forge.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What the module is, on its class (M5 §5.2). It is read from the bytecode when the module is tested and saved:
 * the module never runs to tell what it is.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface ModuleInfo {
    /** Its name in the arsenal and its file's: snake_case, 3 to 32 characters, {@code [a-z][a-z0-9_]{2,31}}; the class is its UpperCamelCase form. */
    String name();

    /** One English line for the Director: what the players see, 120 characters at most. */
    String summary();
}
