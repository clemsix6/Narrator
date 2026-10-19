package io.github.clemsix6.narrator2.forge.api;

/** Why a deployment ends, as {@link ForgeModule#stop} learns it (M5 §6.6). A module disabled by its faults is not told. */
public enum StopReason {
    /** Its time ran out ({@link ModuleContext#endsAt()}). */
    EXPIRED,

    /** The Director or an operator stopped it. */
    STOPPED,

    /** The module ended itself ({@link ModuleContext#end}). */
    ENDED,

    /** An operator switched the Forge off: every module stops. */
    FORGE_OFF,

    /** The server stops; the deployment starts again with the server ({@link ModuleContext#restarted()}), its creatures kept. */
    SERVER_STOPPING,
}
