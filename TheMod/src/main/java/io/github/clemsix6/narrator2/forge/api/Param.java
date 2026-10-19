package io.github.clemsix6.narrator2.forge.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A parameter the Director gives when he deploys the module ({@code deploy_module params}), read through
 * {@link ModuleContext#args()}. One annotation per parameter, on the module's class, 8 at most, in the order the
 * Director reads them (M5 §5.2). The values are checked against these annotations when the module is deployed:
 * the module only ever reads valid ones.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
@Repeatable(ParamList.class)
public @interface Param {
    /** snake_case, unique among the module's parameters: {@code [a-z][a-z0-9_]{0,31}}. */
    String name();

    ParamType type();

    /** What it is, one English line for the Director, 120 characters at most. */
    String doc();

    /** False: the Director may leave it out, and {@link #fallback()} is used. */
    boolean required() default true;

    /**
     * A parameter that is not required: its value when the Director leaves it out, in the text form of its type
     * ({@link ParamType}). Empty: none, and {@link Args} then reads {@code null} for a player, a text or a place,
     * an empty list for players. {@link ParamType#INTEGER}, {@link ParamType#NUMBER} and {@link ParamType#BOOLEAN}
     * need a fallback when they are not required; a required parameter has none.
     */
    String fallback() default "";

    /**
     * The smallest value, inclusive: of an {@link ParamType#INTEGER} or a {@link ParamType#NUMBER}; how many
     * players a {@link ParamType#PLAYERS} has at least; how many characters a {@link ParamType#TEXT} has at least.
     */
    double min() default Double.NEGATIVE_INFINITY;

    /**
     * The largest value, inclusive, as {@link #min()}: how many players at most (8 at most anyway), how many
     * characters a text has at most (300 at most anyway).
     */
    double max() default Double.POSITIVE_INFINITY;

    /** A {@link ParamType#TEXT}: the only values it may take. Empty: any text. */
    String[] choices() default {};
}
