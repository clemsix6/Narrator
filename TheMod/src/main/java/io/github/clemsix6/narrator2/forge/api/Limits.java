package io.github.clemsix6.narrator2.forge.api;

/**
 * The limits a module lives within (M5 §3.6), the same at the bench and in the game. Past a limit of the SDK, the
 * call throws {@link ForgeRefusal} (or an act answers {@code denied}); past a limit of the sandbox, the call is
 * stopped by a {@code ForgeLimit}. The Narrator's tools and guards have their own limits on top of these (the chaos
 * level, no hostile act against a protected player or one who just died…).
 */
public final class Limits {
    /** Server ticks in a second: a delay of {@code 20} is one second. */
    public static final int TICKS_PER_SECOND = 20;

    /** Longest a call of the module may run (constructor, start, stop, handler, timer, callback), milliseconds. */
    public static final int MAX_CALL_MILLIS = 20;

    /** Deepest the module's own calls may nest (recursion included). */
    public static final int MAX_DEPTH = 64;

    /** Most elements a collection (list, set, map, queue) may hold. */
    public static final int MAX_ELEMENTS = 10_000;

    /** Most characters a text ({@code String}, {@code StringBuilder}) may hold. */
    public static final int MAX_CHARS = 65_536;

    /** Most elements an array may hold; also the most a capacity ({@code new ArrayList<>(n)}, {@code ensureCapacity}) may ask. */
    public static final int MAX_ARRAY = 65_536;

    /** Fact subscriptions active at once ({@link Events#on}). */
    public static final int MAX_SUBSCRIPTIONS = 16;

    /** Repeating timers active at once ({@link Timers#every}). */
    public static final int MAX_REPEATING_TIMERS = 16;

    /** Timers waiting at once, repeating ones included. */
    public static final int MAX_PENDING_TIMERS = 64;

    /** Longest delay of a timer, in ticks: one hour. The shortest is one tick. */
    public static final int MAX_DELAY_TICKS = 72_000;

    /** Acts (every {@link Acts} method, {@link Ground#place}, {@link Creature#attack}) in one call of the module. */
    public static final int MAX_ACTS_PER_CALL = 4;

    /** Acts of one deployment in a minute. */
    public static final int MAX_ACTS_PER_MINUTE = 30;

    /** {@link Acts#say}, {@link Acts#sayTo} and {@link Acts#title} of one deployment in a minute (within the server's own speech limits). */
    public static final int MAX_LINES_PER_MINUTE = 4;

    /** {@link Acts#hud} lines a player sees in a second, at most. */
    public static final int MAX_HUD_PER_SECOND = 1;

    /** Creatures of its own (summoned or claimed) a deployment may have at once. */
    public static final int MAX_OWN_CREATURES = 32;

    /** Lines of {@link ModuleContext#log} in a minute; the others are dropped and counted. */
    public static final int MAX_LOG_LINES_PER_MINUTE = 20;

    /** Longest line of {@link ModuleContext#log}, note of {@link ModuleContext#tellDirector}, reason of {@link ModuleContext#end} or note of {@link Game}; longer ones are cut. */
    public static final int MAX_NOTE_CHARS = 200;

    /** Minutes between two notes of {@link ModuleContext#tellDirector}; a note sooner waits and is counted in the next. */
    public static final int DIRECTOR_NOTE_GAP_MINUTES = 5;

    /** Keys a {@link Storage} may hold. */
    public static final int MAX_STORAGE_KEYS = 64;

    /** Longest key of a {@link Storage}. */
    public static final int MAX_STORAGE_KEY_CHARS = 40;

    /** Longest value of a {@link Storage}. */
    public static final int MAX_STORAGE_VALUE_CHARS = 512;

    /** Most characters a {@link Storage} holds, keys and values together. */
    public static final int MAX_STORAGE_CHARS = 8_192;

    /** Cells of one {@link Ground#place}. */
    public static final int MAX_PLACE_CELLS = 64;

    /** Longest a block of {@link Ground#place} stays, in seconds, away from the players (near one, the chaos level caps it lower). */
    public static final int MAX_PLACE_SECONDS = 120;

    /** Widest radius of {@link World#creatures}, in blocks. */
    public static final int MAX_CREATURES_RADIUS = 48;

    /** Most creatures {@link World#creatures} returns: the nearest. */
    public static final int MAX_CREATURES_FOUND = 32;

    /** Farthest a creature may be from an online player to be claimed ({@link Creatures#claim}), in blocks. */
    public static final int MAX_CLAIM_DISTANCE = 32;

    private Limits() {}
}
