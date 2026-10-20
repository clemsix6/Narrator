package io.github.clemsix6.narrator2.forge

import io.github.clemsix6.narrator2.forge.api.Limits

/**
 * The Forge's budgets (M5 §3.6): constants, not settings (`forge.maxActive`, `taskSteps`, `taskMinutes` and `approval`
 * are the only settings, `ForgeConfig`). The limits a module sees are the SDK's own (`forge.api.Limits`, one source,
 * repeated here so that the Kotlin side reads them in one place); the tools', guards' and chaos level's limits add to
 * these. Use them, never a literal.
 */
object ForgeLimits {
    // ── One call of a module (constructor, start, stop, handler, timer, callback): the Meter ─────────────────────

    /** Wall clock of one call: past it, the call is stopped (a fault *limit*). */
    const val CALL_MILLIS = Limits.MAX_CALL_MILLIS
    const val CALL_NANOS = CALL_MILLIS * 1_000_000L

    /** Bytes one call may allocate (when the JVM measures it: `jdk.management`; else the time bounds it alone). */
    const val CALL_ALLOCATION_BYTES = 16L * 1024 * 1024

    /** Frames of the module's own calls. */
    const val MAX_DEPTH = Limits.MAX_DEPTH

    /** The Meter compares the clock and the allocation once every this many ticks. */
    const val METER_CHECK_EVERY = 16

    // ── Sizes at the call sites (Guarded) ──────────────────────────────────────────────────────────────────────

    const val COLLECTION_ELEMENTS = Limits.MAX_ELEMENTS
    const val TEXT_CHARS = Limits.MAX_CHARS
    const val ARRAY_ELEMENTS = Limits.MAX_ARRAY

    /** Largest capacity a call may ask at once (constructors, `ensureCapacity`, `setLength`, `repeat`, the hash tables). */
    const val CAPACITY = Limits.MAX_ARRAY

    /** Digits of a width or a precision in `String.format`/`formatted`. */
    const val FORMAT_DIGITS = 3

    // ── A deployment over time: the host and the engine ─────────────────────────────────────────────────────────

    /** Its code's time in one second; past it [SLOW_SECONDS] seconds in a row is a fault *slowness*. */
    const val DEPLOYMENT_MS_PER_SECOND = 50L
    const val SLOW_SECONDS = 5

    /** All the modules' code in one server tick: past it, a `WARN` once a minute naming the slowest. */
    const val TICK_MS = 10L

    /** Bytes a deployment may allocate in a minute: past it, disabled (a fault *memory*, « allocates too much »). */
    const val ALLOCATION_PER_MINUTE_BYTES = 256L * 1024 * 1024

    /** Memory a deployment may retain, as the gauge estimates it: past it, disabled. */
    const val RETAINED_BYTES = 32L * 1024 * 1024

    /** The gauge's hard ceiling in objects: past it, disabled (« retains too much to measure »). */
    const val GAUGE_MAX_OBJECTS = 200_000

    /** How often the gauge measures a deployment: once a minute, every 10 seconds without allocation metering. */
    const val GAUGE_EVERY_MS = 60_000L
    const val GAUGE_EVERY_MS_UNMETERED = 10_000L

    /** Faults that disable a deployment within [FAULT_WINDOW_MS] (M5 §3.7); a fault in the constructor or `start` disables at once. */
    const val LIMIT_FAULTS = 2
    const val EXCEPTION_FAULTS = 3
    const val MEMORY_FAULTS = 1
    const val FAULT_WINDOW_MS = 10 * 60_000L

    /** A dropped loader still alive this long and this many GC cycles after it: a `WARN` and a decision `forge leak`. */
    const val LEAK_CHECK_MS = 2 * 60_000L
    const val LEAK_GC_CYCLES = 2

    // ── The SDK's limits (forge.api.Limits) ─────────────────────────────────────────────────────────────────────

    const val SUBSCRIPTIONS = Limits.MAX_SUBSCRIPTIONS
    const val REPEATING_TIMERS = Limits.MAX_REPEATING_TIMERS
    const val PENDING_TIMERS = Limits.MAX_PENDING_TIMERS
    const val TIMER_MAX_TICKS = Limits.MAX_DELAY_TICKS

    /** Facts delivered to a deployment: past these, dropped and counted (one decision `facts_dropped` a minute at most). */
    const val FACTS_PER_TICK = 256
    const val FACTS_PER_MINUTE = 3_000

    const val ACTS_PER_CALL = Limits.MAX_ACTS_PER_CALL
    const val ACTS_PER_MINUTE = Limits.MAX_ACTS_PER_MINUTE

    /** `say`, `sayTo` and `title` of a deployment in a minute. */
    const val LINES_PER_MINUTE = Limits.MAX_LINES_PER_MINUTE

    /** `hud`: one line per player and second, 80 characters. */
    const val HUD_GAP_MS = 1_000L / Limits.MAX_HUD_PER_SECOND
    const val HUD_CHARS = 80

    const val OWN_CREATURES = Limits.MAX_OWN_CREATURES
    const val LOG_LINES_PER_MINUTE = Limits.MAX_LOG_LINES_PER_MINUTE

    /** A `log` line, a `tellDirector` note, an `end` reason, a `Game` note. */
    const val NOTE_CHARS = Limits.MAX_NOTE_CHARS
    const val DIRECTOR_NOTE_GAP_MS = Limits.DIRECTOR_NOTE_GAP_MINUTES * 60_000L

    const val STORAGE_KEYS = Limits.MAX_STORAGE_KEYS
    const val STORAGE_KEY_CHARS = Limits.MAX_STORAGE_KEY_CHARS
    const val STORAGE_VALUE_CHARS = Limits.MAX_STORAGE_VALUE_CHARS
    const val STORAGE_CHARS = Limits.MAX_STORAGE_CHARS

    const val PLACE_CELLS = Limits.MAX_PLACE_CELLS
    const val PLACE_MAX_SECONDS = Limits.MAX_PLACE_SECONDS

    /** Near a player (within [PLACE_NEAR_BLOCKS]), `Ground.place` lasts 10 seconds per severity the chaos level allows, at most. */
    const val PLACE_SECONDS_PER_SEVERITY = 10
    const val PLACE_NEAR_BLOCKS = 2

    const val CREATURES_RADIUS = Limits.MAX_CREATURES_RADIUS
    const val CREATURES_FOUND = Limits.MAX_CREATURES_FOUND
    const val CLAIM_DISTANCE = Limits.MAX_CLAIM_DISTANCE

    /** A wild creature that appears this close to an online player is a fact `CreatureAppeared`. */
    const val APPEAR_DISTANCE = 64

    /** Steering a creature: a motion component, a walking speed, a name. */
    const val VELOCITY_MAX = 2.0
    const val SPEED_MIN = 0.3
    const val SPEED_MAX = 2.5
    const val CREATURE_NAME_CHARS = 32

    // ── Deployments ─────────────────────────────────────────────────────────────────────────────────────────────

    /** How long a deployment runs (`deploy_module minutes`): 5 minutes to 7 days. */
    const val DEPLOY_MIN_MINUTES = 5
    const val DEPLOY_MAX_MINUTES = 10_080

    /** How long `deploy_module` waits for the module's first start, to tell whether it started. */
    const val START_WAIT_MS = 1_000L

    // ── Sources, compilation, arsenal (M5 §5, §6.1) ─────────────────────────────────────────────────────────────

    /** A module's name: snake_case, 3 to 32 characters; its class is the UpperCamelCase form. */
    val MODULE_NAME = Regex("[a-z][a-z0-9_]{2,31}")

    /** A parameter's name. */
    val PARAM_NAME = Regex("[a-z][a-z0-9_]{0,31}")

    const val SOURCE_CHARS = 48_000
    const val MAX_CLASSES = 32
    const val MAX_BYTECODE = 256 * 1024
    const val SUMMARY_CHARS = 120
    const val PARAMS = 8
    const val PARAM_DOC_CHARS = 120
    const val SCENARIOS = 8

    /** A compilation is cancelled after this; one that ignores the cancellation this long makes the Forge unavailable (`compiler stuck`). */
    const val COMPILE_MS = 10_000L
    const val COMPILER_STUCK_MS = 60_000L

    /** Built versions kept in memory, the least recently used dropped first. */
    const val BUILD_CACHE = 64

    const val ARSENAL_NAMES = 64
    const val VERSIONS_PER_NAME = 20

    /** Diagnostics in a report, and its length (`forge_test`, M5 §5.3, §8.3). */
    const val DIAGNOSTICS = 20
    const val TEST_REPORT_CHARS = 6_000

    // ── The bench (M5 §6.2, §7) ─────────────────────────────────────────────────────────────────────────────────

    /** A tick at the bench, in milliseconds of its clock. */
    const val BENCH_TICK_MS = 50L

    /** The smoke run: a simulated minute, its three players. */
    const val SMOKE_TICKS = 1_200
    val SMOKE_PLAYERS = listOf("Bartok", "Mimosa", "Josiane")

    /** Every bench run of one `forge_test`, scenarios included. */
    const val BENCH_MS = 30_000L

    /** Memory retained after the smoke run, and its growth over the second half of it: past either, the test fails. */
    const val BENCH_RETAINED_BYTES = 4L * 1024 * 1024
    const val BENCH_RETAINED_GROWTH_BYTES = 1L * 1024 * 1024

    // ── What the Director and the forger read (M5 §8, §9) ──────────────────────────────────────────────────────

    /** The `Arsenal` section of the briefing's `<engine_state>`. */
    const val ARSENAL_CHARS = 1_200

    /** The `Modules:` line of the situation. */
    const val MODULES_LINE = 400

    /** `forge_module`'s brief. */
    const val BRIEF_MIN_CHARS = 40
    const val BRIEF_MAX_CHARS = 3_000

    /** `game_ids`: ids in one answer. */
    const val GAME_IDS = 150

    /** The forger's task (with `forge.taskSteps` and `forge.taskMinutes`) and its report. */
    const val REPORT_CHARS = 3_000
}
