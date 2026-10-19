package io.github.clemsix6.narrator2.forge.sim

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.forge.api.GameFact
import io.github.clemsix6.narrator2.forge.api.ParamType
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.api.Who
import io.github.clemsix6.narrator2.tool.ToolText
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The smoke run of `forge_test` (M5 §6.2, step 1): the module deployed on a bench of three players (Bartok, Mimosa,
 * Josiane, and a wild zombie near them), its required parameters given an example of their type ([examples]), then
 * one example fact of each class it subscribed to in `start` ([sample]), then a simulated minute
 * (`ForgeLimits.SMOKE_TICKS`), then `stop(EXPIRED)`. It fails on the bench's first failure (a fault, a call over
 * 20 ms, slowness), and on the memory the module retains after the minute (more than
 * `ForgeLimits.BENCH_RETAINED_BYTES`) or that still grew over its second half (more than
 * `ForgeLimits.BENCH_RETAINED_GROWTH_BYTES`), as [BenchHost.retained] estimates it.
 */
internal class SmokeRun(private val host: BenchHost, private val specs: List<ParamSpec>, private val deadline: Long, private val budgetMs: Long) {
    fun run(): SmokeResult {
        val bench = BenchImpl(host, specs, deadline, budgetMs, PHASE, ForgeLimits.DEPLOY_MIN_MINUTES)
        var refused: String? = null
        var half: Long? = null
        var end: Long? = null
        try {
            PLAYERS.forEach { (name, at) -> bench.player(name, at) }
            val zombie = bench.creature(ZOMBIE, ZOMBIE_AT)
            bench.start(*examples(specs).toTypedArray())
            val deployment = bench.deployment
            deployment?.subscribedTypes()?.forEach { type -> sample(type, bench, zombie)?.let { bench.fact(it) } }
            bench.run(HALF)
            half = bench.retained()
            bench.run(ForgeLimits.SMOKE_TICKS - HALF)
            end = bench.retained()
            bench.expire()
        } catch (stop: BenchStop) {
            // The failure is the bench's.
        } catch (refusal: ForgeRefusal) {
            refused = "$PHASE: its example parameters were refused (${refusal.message}): check the @Param bounds and fallbacks"
        }
        return SmokeResult(bench, refused, memory(half, end), end)
    }

    /** The problems of the memory the module retains: [end] after the minute, grown from [half] over its second half. */
    private fun memory(half: Long?, end: Long?): List<String> = buildList {
        if (end == null) return@buildList
        if (end > ForgeLimits.BENCH_RETAINED_BYTES) {
            add(
                "memory in $PHASE: it retains ${mib(end)} MiB after a minute, the most is ${mib(ForgeLimits.BENCH_RETAINED_BYTES)} MiB: " +
                    "keep less, drop what is over",
            )
        }
        if (half != null && end - half > ForgeLimits.BENCH_RETAINED_GROWTH_BYTES) {
            add(
                "memory in $PHASE: what it retains grew by ${mib(end - half)} MiB over the second half of the minute, the most is " +
                    "${mib(ForgeLimits.BENCH_RETAINED_GROWTH_BYTES)} MiB: something piles up and is never dropped",
            )
        }
    }

    companion object {
        const val PHASE = "smoke"

        /** The smoke's players, where they stand, and the wild zombie a module may claim. */
        val PLAYERS: List<Pair<String, Place>> = ForgeLimits.SMOKE_PLAYERS.zip(
            listOf(Place("overworld", 0.5, 64.0, 0.5), Place("overworld", 6.5, 64.0, 0.5), Place("overworld", 0.5, 64.0, 6.5)),
        )
        const val ZOMBIE = "zombie"
        val ZOMBIE_AT = Place("overworld", 10.5, 64.0, 10.5)

        /** The tick the retained memory is measured first, half the simulated minute. */
        const val HALF = ForgeLimits.SMOKE_TICKS / 2

        /** A creature of the facts that is no creature of the bench. */
        private const val STRANGER = "00000000-0000-4000-8000-00000000c41c"

        /**
         * The required parameters of [specs] with an example value of their type, in their text form: a player is
         * Bartok, players Bartok and Mimosa (within their bounds), a whole number or a number its minimum (or 1), a
         * text its first choice (or a French word), a boolean false, a place near Bartok. The others read their fallback.
         */
        fun examples(specs: List<ParamSpec>): List<String> = specs.filter { it.required }.map { "${it.name}=${example(it)}" }

        private fun example(spec: ParamSpec): String = when (spec.type) {
            ParamType.PLAYER -> ForgeLimits.SMOKE_PLAYERS.first()
            ParamType.PLAYERS -> {
                val count = 2.coerceAtLeast(spec.min?.toInt() ?: 1).coerceAtMost(minOf(spec.max?.toInt() ?: Int.MAX_VALUE, ForgeLimits.SMOKE_PLAYERS.size))
                ForgeLimits.SMOKE_PLAYERS.take(count).joinToString(",")
            }
            ParamType.INTEGER -> {
                val value = spec.min?.let { ceil(it) } ?: 1.0
                val max = spec.max
                (if (max != null && value > max) floor(max) else value).toLong().toString()
            }
            ParamType.NUMBER -> {
                val value = spec.min ?: 1.0
                val max = spec.max
                ToolText.number(if (max != null && value > max) max else value)
            }
            ParamType.TEXT -> spec.choices.firstOrNull() ?: text(spec)
            ParamType.BOOLEAN -> "false"
            ParamType.PLACE -> "8 64 0"
        }

        private fun text(spec: ParamSpec): String {
            val least = spec.min?.toInt() ?: 0
            val most = spec.max?.toInt() ?: Int.MAX_VALUE
            return TEXT.padEnd(least, '!').take(most.coerceAtLeast(least))
        }

        private const val TEXT = "bonjour"

        /**
         * An example fact of [type] for the smoke run, about the smoke's players and its [zombie]; a creature gone is
         * one of the module's own when it has some. Null for a class the bench does not know.
         */
        fun sample(type: Class<*>, bench: BenchImpl, zombie: String): GameFact? {
            val (bartok, mimosa, josiane) = ForgeLimits.SMOKE_PLAYERS
            val world = bench.world
            fun at(name: String): Place = world.player(name)?.at ?: PLAYERS.first().second
            val walker = Who(ZOMBIE, zombie, null)
            val gone = bench.deployment?.own?.firstOrNull()?.let { world.creatures[it] }
            return when (type) {
                GameFact.BlockBroken::class.java -> GameFact.BlockBroken(bartok, at(bartok), "stone", false)
                GameFact.BlockPlaced::class.java -> GameFact.BlockPlaced(bartok, at(bartok), "oak_planks")
                GameFact.CreatureHit::class.java -> GameFact.CreatureHit(bartok, at(bartok), walker, 4f, 16f)
                GameFact.PlayerHit::class.java -> GameFact.PlayerHit(bartok, at(bartok), mimosa, 2f)
                GameFact.PlayerHurt::class.java -> GameFact.PlayerHurt(mimosa, at(mimosa), "mob_attack", walker, 3f)
                GameFact.CreatureKilled::class.java -> GameFact.CreatureKilled(bartok, at(bartok), Who("skeleton", STRANGER, null))
                GameFact.ProjectileHit::class.java ->
                    GameFact.ProjectileHit(josiane, at(josiane), "arrow", Who("player", world.player(josiane)?.uuid, josiane), walker)
                GameFact.PlayerDied::class.java -> GameFact.PlayerDied(josiane, at(josiane), "fall", null)
                GameFact.Chat::class.java -> GameFact.Chat(mimosa, at(mimosa), "bonjour tout le monde")
                GameFact.DimensionChanged::class.java -> GameFact.DimensionChanged(josiane, Place("the_nether", 0.5, 70.0, 0.5), "overworld", "the_nether")
                GameFact.StructureEntered::class.java -> GameFact.StructureEntered(bartok, at(bartok), "village_plains")
                GameFact.BiomeEntered::class.java -> GameFact.BiomeEntered(bartok, at(bartok), "forest")
                GameFact.Advancement::class.java -> GameFact.Advancement(mimosa, at(mimosa), "story/mine_stone")
                GameFact.Crafted::class.java -> GameFact.Crafted(mimosa, at(mimosa), "bread", 2)
                GameFact.Consumed::class.java -> GameFact.Consumed(josiane, at(josiane), "bread")
                GameFact.Slept::class.java -> GameFact.Slept(josiane, at(josiane))
                GameFact.Joined::class.java -> GameFact.Joined(josiane, at(josiane))
                GameFact.Left::class.java -> GameFact.Left(josiane, at(josiane))
                GameFact.CreatureAppeared::class.java -> GameFact.CreatureAppeared(bartok, ZOMBIE_AT, walker)
                GameFact.CreatureGone::class.java -> GameFact.CreatureGone(
                    null, gone?.at ?: at(bartok), gone?.let { Who(it.type, it.uuid, it.name) } ?: Who("chicken", STRANGER, null), "vanished",
                )
                else -> null
            }
        }

        private fun mib(bytes: Long): String = ToolText.number(bytes / BYTES_PER_MIB)

        private const val BYTES_PER_MIB = 1024.0 * 1024.0
    }
}

/**
 * What the smoke run found.
 *
 * @property failure its first failure, as the forger reads it; null when it ran to the end.
 * @property memory the problems of the memory it retains.
 * @property retained what it retained after the minute; null when unknown (no gauge, or it did not run that long).
 */
internal class SmokeResult(bench: BenchImpl, refused: String?, val memory: List<String>, val retained: Long?) {
    val failure: String? = bench.failure ?: refused
    val cancelled: Boolean = bench.cancelled
    val ticks: Int = bench.world.tick
    val slowestNanos: Long = bench.slowestNanos
    val endedItselfAt: Int? = bench.endedItselfAt
    private val deployment = bench.deployment
    val facts: Int = deployment?.factsDelivered ?: 0
    val acts: Map<String, Int> = deployment?.counts.orEmpty()
    val actsOverLimits: Int = deployment?.actsOverLimits ?: 0
    val logsDropped: Int = deployment?.logsDropped ?: 0
    val factsDropped: Int = deployment?.factsDropped ?: 0
    val dry: Collection<DryCall> = deployment?.dry?.values.orEmpty()
    val dryDropped: Int = deployment?.dryDropped ?: 0
}
