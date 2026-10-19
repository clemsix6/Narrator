package io.github.clemsix6.narrator2.forge.api;

/**
 * The type of a {@link Param}: how the Director writes its value in {@code deploy_module params} (JSON), how a
 * {@link Param#fallback()} and a bench's {@code start("name=value")} write it (text), and how {@link Args} reads it.
 * The Director may also give any value in its text form, as a JSON string.
 */
public enum ParamType {
    /** A player the server knows (who played on it), online or not. JSON: {@code "Bartok"}; text: {@code Bartok}. Read by {@link Args#player}. */
    PLAYER,

    /**
     * Players the server knows, each once, 8 at most ({@link Param#min()} and {@link Param#max()}: how many). JSON:
     * {@code ["Bartok", "Mimosa"]}; text: {@code Bartok,Mimosa}. Read by {@link Args#players}.
     */
    PLAYERS,

    /** A whole number within {@link Param#min()} and {@link Param#max()}. JSON: {@code 6}; text: {@code 6}. Read by {@link Args#integer}. */
    INTEGER,

    /** A number within {@link Param#min()} and {@link Param#max()}. JSON: {@code 2.5}; text: {@code 2.5}. Read by {@link Args#number}. */
    NUMBER,

    /**
     * A text, 300 characters at most, French when players read it; one of {@link Param#choices()} when it has
     * some. JSON: {@code "chicken"}; text: as is. Read by {@link Args#text}.
     */
    TEXT,

    /** JSON: {@code true}; text: {@code true} or {@code false}. Read by {@link Args#bool}. */
    BOOLEAN,

    /**
     * A place in a dimension the server has. JSON: {@code {"x": 120, "y": 64, "z": -30, "dimension": "the_nether"}}
     * (the overworld when the dimension is left out), or an online player's name: where they stand when the
     * module is deployed. Text: {@code 120 64 -30}, {@code 120 64 -30 the_nether}, or a player's name. Read by
     * {@link Args#place}.
     */
    PLACE,
}
