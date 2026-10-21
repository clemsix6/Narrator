package io.github.clemsix6.narrator2.tool.storyteller

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.memory.IdeaDraft
import io.github.clemsix6.narrator2.memory.IdeaKind
import io.github.clemsix6.narrator2.memory.MemoryLimits
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.style.ComicRegister
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.Items
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.Range
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.memory.MemoryTool
import io.github.clemsix6.narrator2.tool.memory.NAME_MAX
import io.github.clemsix6.narrator2.tool.memory.PLAYERS_MAX
import io.github.clemsix6.narrator2.tool.memory.REF_MAX
import io.github.clemsix6.narrator2.tool.memory.author
import io.github.clemsix6.narrator2.tool.memory.knownPlayers
import io.github.clemsix6.narrator2.tool.memory.memory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The storyteller's own tools (M6 §6.5): `propose_idea` and `drop_idea`, the ideas of its programme (`P-…`). Memory
 * tools: shown while the memory runs, never to the Director, effect `ENGINE` (they write the memory, never the world).
 * Their author is the storyteller ([author]): the run of the dream's programme, or its task. What they write goes
 * through the store, which checks it (`MemoryStore.addIdea`, `dropIdea`).
 */
@Suppress("UNUSED_PARAMETER") // every family has this signature
fun storytellerTools(services: NarratorServices): List<NarratorTool<*>> = listOf(ProposeIdeaTool(), DropIdeaTool())

@Serializable
data class ProposeIdeaArgs(
    @Doc(
        "season (the thread of the coming sessions, one open at most), arc_step (the next step of an arc: cite it in refs), " +
            "challenge, duel, punishment, favor, law, plan, module (a ready module of the arsenal: name it in module), " +
            "forge (a module to order: its brief), speech (a prophecy, a gag back, a dream to tell), twist.",
    )
    val kind: IdeaKind,
    @Doc("A short title, in French.")
    @MaxLen(MemoryLimits.IDEA_TITLE)
    val title: String,
    @Doc("What happens and what players see; French, impersonal or third person, never the Narrator's voice.")
    @MaxLen(MemoryLimits.IDEA_PITCH)
    val pitch: String,
    @Doc("When it fits: who must be online, which moment.")
    @MaxLen(MemoryLimits.IDEA_FIT)
    val fit: String,
    @Doc("The players it is for, by name; none: whoever is online.")
    @Items(0, PLAYERS_MAX)
    @MaxLen(NAME_MAX)
    val players: List<String> = emptyList(),
    @Doc("The comic register it suggests.")
    val register: ComicRegister? = null,
    @Doc("Your honest notes, 1 to 5, an average idea being 3: novelty, against what was done and dealt lately.")
    @Range(1.0, 5.0)
    val novelty: Int,
    @Doc("Fun for the players.")
    @SerialName("fun")
    @Range(1.0, 5.0)
    val funScore: Int,
    @Doc("Fairness to the players it targets.")
    @Range(1.0, 5.0)
    val fairness: Int,
    @Doc("Memory rows it builds on: C-…, M-…, H-….")
    @Items(0, MemoryLimits.IDEA_REFS)
    @MaxLen(REF_MAX)
    val refs: List<String> = emptyList(),
    @Doc("kind module: the arsenal's module.")
    @MaxLen(MODULE_MAX)
    val module: String? = null,
    @Doc("kind forge: the brief for forge_module (what players see, who, how long, how it ends, what it must never do).")
    @MaxLen(MemoryLimits.IDEA_BRIEF)
    val brief: String? = null,
    @Doc("World play hours before it expires.")
    @Range(1.0, 168.0)
    val hours: Int = MemoryLimits.IDEA_DEFAULT_PLAY_HOURS,
)

/**
 * `propose_idea`: writes one idea of the programme, `open`, never told to anyone (M6 §6.2, §12.4). The players are
 * found by name ([knownPlayers]), the refs parsed; an idea `module` names a module ready in the arsenal, and an idea
 * `module` or `forge` needs the Forge running (the arsenal is not in the database: the store cannot check it). The
 * store checks the rest (`DuckMemoryStore.addIdea`). The answer says the ideas the new one closed (the season it
 * replaced, the idea it superseded beyond the twelve) and how many are open, as the store returns them.
 */
class ProposeIdeaTool : MemoryTool<ProposeIdeaArgs>(
    name = "propose_idea",
    description = "Writes one idea of your programme (P-…): something the Narrator may do with the players, fitted to " +
        "their real progress; he takes it, bends it or ignores it, and nobody else ever reads it. Never an order nor a " +
        "promise. Refused when it is too close to a recent idea or takes the title of an open one, cites what does not exist, or asks what the world does " +
        "not allow. Returns its id.",
    args = ProposeIdeaArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: ProposeIdeaArgs): ToolResult {
        val refs = args.refs.map { text ->
            Ref.parse(text) ?: return ToolResult.invalid("refs: ${ModelText.quote(text, REF_MAX)} is not a row of the memory: cite C-45, M-812, H-3 as given")
        }
        lever(ctx, args)?.let { return it }
        val players = ctx.knownPlayers(args.players, "players")
        val draft = IdeaDraft(
            kind = args.kind,
            title = args.title.trim(),
            pitch = args.pitch.trim(),
            fit = args.fit.trim(),
            players = players.map { it.uuid },
            register = args.register,
            novelty = args.novelty,
            funScore = args.funScore,
            fairness = args.fairness,
            refs = refs,
            module = args.module?.trim(),
            brief = args.brief?.trim(),
            playHours = args.hours,
        )
        val added = ctx.memory.store.addIdea(draft, ctx.author())
        val idea = added.row
        log.info("${idea.ref} ${idea.kind.id} « ${idea.title} » written by ${ctx.caller.conversationId}${added.closed.joinToString("") { "; ${it.ref} ${it.status.id}" }}")
        val also = added.closed.joinToString("") { "; ${it.ref} ${it.kind.id} « ${ModelText.escape(it.title)} » ${it.status.id}: ${ModelText.escape(it.closeReason.orEmpty())}" }
        val who = if (idea.players.isEmpty()) "whoever is online" else ToolText.names(idea.players)
        return ToolResult.ok(
            "ok: ${idea.ref} ${idea.kind.id} « ${ModelText.escape(idea.title)} » for $who, open for ${args.hours}h of world play$also; " +
                "${added.open} ${if (added.open == 1) "idea" else "ideas"} open",
        )
    }

    /**
     * What the store cannot check of an idea's lever: the Forge runs for an idea `module` or `forge`, and an idea
     * `module` names a module ready in the arsenal. Null when it holds.
     */
    private fun lever(ctx: ToolContext, args: ProposeIdeaArgs): ToolResult? {
        if (args.kind != IdeaKind.MODULE && args.kind != IdeaKind.FORGE) return null
        val forge = ctx.services.forge
        if (!forge.available) {
            return ToolResult.invalid("the Forge is off (${forge.unavailableReason ?: "no reason given"}): no module can be deployed nor ordered; propose another kind")
        }
        val name = args.module?.trim() ?: return null // the store asks for it
        if (args.kind != IdeaKind.MODULE || forge.arsenal.entry(name) != null) return null
        val ready = forge.arsenal.list().filter { it.status == ModuleStatus.READY }.map { it.name }.distinct()
        return ToolResult.invalid(
            "module: no module ${ModelText.quote(name, MODULE_MAX)} is ready in the arsenal; ready: ${ready.joinToString().ifEmpty { "none" }} " +
                "(a module to order is an idea forge, with its brief)",
        )
    }
}

@Serializable
data class DropIdeaArgs(
    @Doc("The idea to close, e.g. P-41.")
    @MaxLen(IDEA_REF_MAX)
    val id: String,
    @Doc("Why it no longer holds, in French: the moment passed, the players moved on, a better idea replaces it.")
    @MaxLen(MemoryLimits.IDEA_DROP_REASON)
    val reason: String,
)

/** `drop_idea`: closes an open idea of the programme, with its reason; nothing is deleted (M6 §6.2). */
class DropIdeaTool : MemoryTool<DropIdeaArgs>(
    name = "drop_idea",
    description = "Closes one of your open ideas that no longer holds, with why. It is kept, and no longer dealt to the " +
        "Narrator.",
    args = DropIdeaArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: DropIdeaArgs): ToolResult {
        val ref = Ref.parse(args.id)?.takeIf { it.kind == RefKind.IDEA }
            ?: return ToolResult.invalid("id must be one of your ideas, like P-41 (got ${ModelText.quote(args.id, IDEA_REF_MAX)})")
        val idea = ctx.memory.store.dropIdea(ref, args.reason.trim(), ctx.author())
        log.info("${idea.ref} dropped by ${ctx.caller.conversationId}")
        return ToolResult.ok("ok: ${idea.ref} ${idea.kind.id} « ${ModelText.escape(idea.title)} » dropped; it is no longer dealt to the Narrator")
    }
}

private val log = Log.story

/** Longest name of a module of the arsenal. */
private const val MODULE_MAX = 32
