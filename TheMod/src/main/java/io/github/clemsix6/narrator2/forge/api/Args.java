package io.github.clemsix6.narrator2.forge.api;

import java.util.List;

/**
 * The module's parameters ({@link Param}), as the Director gave them when he deployed it and as they were checked
 * then: within their bounds and choices, their players known to the server. A parameter left out reads its
 * {@link Param#fallback()}. Reading a name the module does not declare, or with the method of another type, throws
 * {@link ForgeRefusal}.
 */
public interface Args {
    /** A {@link ParamType#PLAYER}: the player's name (online or not); null when left out without a fallback. */
    String player(String name);

    /** A {@link ParamType#PLAYERS}: their names, in the order given; empty when left out without a fallback. */
    List<String> players(String name);

    /** An {@link ParamType#INTEGER}. */
    int integer(String name);

    /** A {@link ParamType#NUMBER} (an {@link ParamType#INTEGER} reads too). */
    double number(String name);

    /** A {@link ParamType#TEXT}; null when left out without a fallback. */
    String text(String name);

    /** A {@link ParamType#BOOLEAN}. */
    boolean bool(String name);

    /** A {@link ParamType#PLACE}: where a player's name stood when the module was deployed; null when left out without a fallback. */
    Place place(String name);
}
