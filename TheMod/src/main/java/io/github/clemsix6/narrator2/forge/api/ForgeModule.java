package io.github.clemsix6.narrator2.forge.api;

/**
 * A module: a game mechanic the Narrator deploys for some minutes (M5 §5.2).
 *
 * <p>The module is one Java file, without a {@code package} declaration, whose one public top-level class
 * implements this interface, is named after the module in UpperCamelCase ({@code orbiting_chickens} →
 * {@code OrbitingChickens}), is annotated {@link ModuleInfo} (and {@link Param} for each parameter), and has a
 * public constructor without arguments. Nested, local and anonymous classes, records and enums are free; an
 * optional {@code public static} nested class {@code Scenarios} holds its tests ({@code public void x(Bench b)},
 * see {@code io.github.clemsix6.narrator2.forge.api.test.Bench}).
 *
 * <p>Each deployment gets a new instance. Its static initializer and its constructor run measured, like every
 * call. Keep state in fields; what must survive a restart of the server goes to {@link ModuleContext#storage()}.
 */
public interface ForgeModule {
    /**
     * Once on the server thread when the module is deployed, and again after a server restart
     * ({@link ModuleContext#restarted()}): subscribe to facts, schedule timers, spawn or claim creatures. Throwing
     * here disables the deployment at once.
     */
    void start(ModuleContext ctx);

    /**
     * Once when the deployment ends, for any reason but a fault (a disabled module is not called). It may still
     * read the world and its storage; acts asked here are dropped, and timers and subscriptions are refused. Its
     * creatures are taken back after it returns (but at {@link StopReason#SERVER_STOPPING}, when they stay for the
     * restart).
     */
    default void stop(ModuleContext ctx, StopReason why) {}
}
