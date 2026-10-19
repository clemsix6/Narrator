package io.github.clemsix6.narrator2.mechanics.rule

import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.Mechanics
import io.github.clemsix6.narrator2.mechanics.RuleControl
import io.github.clemsix6.narrator2.mechanics.RuleSpec
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.memory.Memory
import io.github.clemsix6.narrator2.memory.MemoryStore
import io.github.clemsix6.narrator2.memory.StoreFixture
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.FakeGuards
import io.github.clemsix6.narrator2.testing.FakeMechanics
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.action.PunishArgs
import io.github.clemsix6.narrator2.tool.action.playerActionTools
import io.github.clemsix6.narrator2.tool.action.punishTools
import io.github.clemsix6.narrator2.tool.action.worldActionTools
import io.github.clemsix6.narrator2.tool.speech.speechTools
import kotlinx.coroutines.runBlocking
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import kotlin.test.assertFailsWith

/**
 * The rules of a test (M4 §8): a real [RuleBook] on a state in memory, its canon in a real store on an
 * in-memory database (Bartok and Mimosa have played), the real tools of the rules for the dry checks (the game's
 * registries are bootstrapped), the ids of [FakeRuleIds], and the Director's caller of [MechanicsSamples].
 * [services] has a mechanics engine whose rules are [book], for the tools.
 */
internal class RuleFixture : AutoCloseable {
    val base = StoreFixture()
    val clock = base.clock
    val guards = FakeGuards(clock = clock)
    val director = FakeDirector(clock)
    private val fakeMemory = FakeMemory(clock)
    val memory: Memory = object : Memory by fakeMemory {
        override val store: MemoryStore get() = base.store
    }
    private val mechanics = BookMechanics(FakeMechanics(clock))
    val services = TestServices(clock = clock, journal = base.journal, memory = memory, guards = guards, director = director, mechanics = mechanics)
    val actions = EngineActions(
        services,
        speechTools(services) + playerActionTools(services) + worldActionTools(services) + punishTools(services).ifEmpty { listOf(PunishStandIn()) } + ruleOnlyTools(),
    )
    val ids = FakeRuleIds()
    val store = StateStore.inMemory()
    val book = RuleBook(services, store, RuleValidator(services, actions, ids))
    val caller = MechanicsSamples.director

    init {
        mechanics.book = book
    }

    /** [json] decoded as `create_rule` decodes it. */
    fun spec(json: String): RuleSpec = when (val decoded = ToolArgs.decode(RuleSpec.serializer(), json)) {
        is ToolArgs.Decoded.Ok -> decoded.args
        is ToolArgs.Decoded.Invalid -> error("the rule does not decode: ${decoded.problem}")
    }

    /** What the engine answers to the rule [json], refused: as the tool shows it. */
    fun refusal(json: String): String = runBlocking { assertFailsWith<MechanicRejected> { book.create(spec(json), caller) }.toResult().text }

    override fun close() = base.close()

    companion object {
        init {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}

/** A mechanics engine whose rules are a real [RuleBook] (set once built); the rest is [inner]'s. */
internal class BookMechanics(private val inner: Mechanics) : Mechanics by inner {
    lateinit var book: RuleBook
    override val rules: RuleControl get() = book
}

/**
 * `punish` while M4-13's own tool is not there: its arguments, no dry check. The fixture takes the real one as
 * soon as `punishTools` has it.
 */
class PunishStandIn : NarratorTool<PunishArgs>("punish", "A stand-in of punish for the tests.", PunishArgs.serializer(), ToolEffect.WORLD) {
    override suspend fun run(ctx: ToolContext, args: PunishArgs): ToolResult = ToolResult.failed("a stand-in of punish")
}

/** A few ids of each kind, and their tags, as a server would know them. */
class FakeRuleIds : RuleIds {
    private val known: Map<RuleIdKind, List<String>> = mapOf(
        RuleIdKind.ENTITY to listOf(
            "skeleton", "silverfish", "cow", "creeper", "villager", "zombie", "zombie_villager", "zombified_piglin", "player", "#skeletons", "#undead",
        ),
        RuleIdKind.BLOCK to listOf("diamond_ore", "deepslate_diamond_ore", "stone", "#diamond_ores", "#logs"),
        RuleIdKind.ITEM to listOf("bread", "diamond", "#logs"),
        RuleIdKind.STRUCTURE to listOf("village_plains", "stronghold", "#village"),
        RuleIdKind.BIOME to listOf("plains", "desert", "#is_ocean"),
        RuleIdKind.ADVANCEMENT to listOf("story/mine_diamond", "nether/root"),
        RuleIdKind.DIMENSION to listOf("overworld", "the_nether", "the_end"),
    )

    override fun resolve(kind: RuleIdKind, raw: String): String? {
        val text = raw.trim().lowercase()
        if (kind == RuleIdKind.DIMENSION) return ToolText.dimensionId(text).takeIf { it.removePrefix("minecraft:") in known.getValue(kind) }
        val tag = text.startsWith("#")
        val short = text.removePrefix("#").removePrefix("minecraft:")
        return if ((if (tag) "#$short" else short) in known.getValue(kind)) (if (tag) "#minecraft:$short" else "minecraft:$short") else null
    }

    override fun known(kind: RuleIdKind): Collection<String> = known.getValue(kind)
}
