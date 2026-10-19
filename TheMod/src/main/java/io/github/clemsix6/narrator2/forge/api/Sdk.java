package io.github.clemsix6.narrator2.forge.api;

/**
 * The Narrator's SDK: everything a module sees (M5 §4). A module is compiled against this package and an
 * allow-list of the JDK, nothing else: no class of the game, of Fabric or of the mod. Everything it does to the
 * world goes through the SDK, hence through the Narrator's own tools and guards.
 *
 * <h2>Conventions</h2>
 * <ul>
 *   <li><b>Ids</b> are written as the Narrator's tools write them: without the {@code minecraft:} namespace
 *       ({@code cow}, {@code diamond_ore}, {@code overworld}, {@code the_nether}, {@code story/mine_stone}); another
 *       mod's ids keep their namespace ({@code farmersdelight:tomato}). Every id the SDK takes may also be written
 *       with {@code minecraft:}; a dimension may also be {@code nether} or {@code end}.</li>
 *   <li><b>Players</b> are names, as the game shows them ({@code Bartok}). <b>Creatures</b> are UUIDs, as text.</li>
 *   <li><b>Times</b> are epoch milliseconds of the server's clock ({@link ModuleContext#now()}); <b>delays</b> are
 *       server ticks, {@link Limits#TICKS_PER_SECOND} a second.</li>
 *   <li><b>Lists</b> the SDK returns are unmodifiable; lists it takes are copied when it takes them.</li>
 *   <li><b>Threads</b>: all the code of a module runs on the server thread (on a worker thread at the bench), one
 *       call at a time: its constructor, {@link ForgeModule#start}, {@link ForgeModule#stop}, each fact handler,
 *       each timer, each callback. Each call is measured ({@link Limits#MAX_CALL_MILLIS} ms at most). Nothing in the
 *       SDK blocks: reads are immediate, acts are queued and answer later ({@link Pending}). Never wait in a loop:
 *       schedule ({@link Timers}).</li>
 *   <li><b>Refusals</b>: an SDK call that cannot be done throws {@link ForgeRefusal} (a {@code RuntimeException}
 *       the module may catch): a limit of the SDK, a bad argument, a module that is over. An act is never refused by
 *       an exception: its {@link Outcome} says so. A limit of the sandbox (time, memory, depth, sizes) throws
 *       {@code ForgeLimit}, an {@code Error} that ends the call; two of them in ten minutes disable the module.</li>
 *   <li><b>Players read</b> what a module says ({@code say}, titles, the action bar, notes to the Director, the
 *       reason of its end) in French, in the Narrator's voice: sarcastic, funny, stern, never gruesome, short.</li>
 * </ul>
 *
 * <h2>What the SDK never does (version 1)</h2>
 * Give, take, drop or read a whole inventory (only {@link World#count}); run a command; change the weather, the
 * time or a game rule; read a private message or a prayer; talk to another module; touch a file or the network;
 * handle an entity that is not a creature; change a block other than in thin air ({@link Ground}); cancel or change
 * what happens in the game (damage, a death, a click).
 *
 * <h2>Stability</h2>
 * {@link #VERSION} is 1. {@link ForgeModule} is the only interface a module implements: methods may be added to
 * the other interfaces and types may be added without a new version. A removal or a change of a signature makes
 * version 2: every module is compiled again at the next start, and a version that no longer compiles is
 * {@code broken}.
 *
 * <h2>For the implementers of the SDK (M5 §4.1)</h2>
 * Every object a module hands over is opaque: the engine and the SDK never call one of its methods
 * ({@code toString}, {@code equals} and {@code hashCode} included) outside a call armed by the host, never use it
 * as a key of a map or a set of their own, never log it. Lists are copied, and {@code Consumer}s and
 * {@code Runnable}s called, inside an armed call only. A {@code ForgeLimit} or a {@code StackOverflowError} thrown
 * in the middle of an SDK call must leave no shared state half changed: acts, ends and wins are queued and done
 * after the call; the only immediate writes are the module's {@link Storage} and the steering of its own
 * {@link Creature}s.
 */
public final class Sdk {
    /** The SDK's major version (M5 §4.1); a module's manifest records the version it was compiled against. */
    public static final int VERSION = 1;

    private Sdk() {}
}
