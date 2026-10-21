package io.github.clemsix6.narrator2.tool.speech

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.guard.ItemLedger
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.observer.ref
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.Items
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.Range
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.notOnline
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack

/**
 * Speech and staging tools (M1_M2 §9.1): `say`, `show_title`, `actionbar`, `play_sound`, `give_book`.
 * `ToolCatalog` builds the family once per conversation; the [SpeechLimiter] is the server's
 * (`ToolLimits.speech`), shared by every conversation.
 *
 * Every text the players see is cleaned by [Speech.clean] and shown as a literal component, with the
 * Narrator's prefix and color from `UiTexts`. Every text line (not the sounds) is journaled as a
 * [NarratorSpoke] of channel `narrator`, published on the server thread just before the players get
 * it: the `chat` table is the Narrator's memory of what it said. Titles, lines above the hotbar and
 * books are recorded with their kind first: `[title] …`, `[actionbar] …`, `[book "…"] …`.
 *
 * The tools a rule of the mechanics engine may use (`say`, `show_title`, `actionbar`, `play_sound`, M4 §8.4)
 * keep the checks that need neither the players online nor the world in `check` (an empty text, an unknown
 * sound), which `run` starts with: the engine refuses a rule's effect at its creation with the same answer.
 *
 * In a turn of the Director, `say` refuses once a line that opens like too many of its latest (the watch of the style,
 * M6 §3.5); titles, lines above the hotbar and books are never checked.
 */
fun speechTools(services: NarratorServices): List<NarratorTool<*>> {
    val limiter = services.toolLimits.speech
    return listOf(SayTool(limiter), ShowTitleTool(limiter), ActionbarTool(limiter), PlaySoundTool(limiter), GiveBookTool(limiter))
}

private const val TO_DOC = "Names of online players; everyone online when left out."

// ── say ─────────────────────────────────────────────────────────────────────────────────────────

@Serializable
data class SayArgs(
    @Doc("What the Narrator says, in plain text: one to three sentences. A newline starts a new line (3 lines at most).")
    @MaxLen(300)
    val text: String,
    @Doc("Names of the online players who get it privately; everyone online hears it when left out.")
    @Items(1, 8)
    @MaxLen(16)
    val to: List<String>? = null,
)

class SayTool(private val limiter: SpeechLimiter) : NarratorTool<SayArgs>(
    name = "say",
    description = "Speaks in the chat as the Narrator: to everyone online, or privately to some players (only they see it). " +
        "The chat is narrow and players read while playing: keep it short. At most 4 lines per turn and 8 per minute.",
    args = SayArgs.serializer(),
    effect = ToolEffect.SPEECH,
) {
    /** The Director's turn whose line this tool refused for its opener: its next lines are not checked (M6 §3.5). */
    @Volatile
    private var refusedTurn: Long? = null

    override suspend fun check(ctx: ToolContext, args: SayArgs): ToolResult? =
        if (Speech.clean(args.text, Speech.CHAT_LINES).isEmpty()) ToolResult.invalid(nothingLeft("text")) else null

    override suspend fun run(ctx: ToolContext, args: SayArgs): ToolResult {
        check(ctx, args)?.let { return it }
        val text = Speech.clean(args.text, Speech.CHAT_LINES)
        val audience = audience(ctx, args.to)
        audience.problem?.let { return it }
        restingOpener(ctx, text)?.let { return it }
        limiter.line(ctx.caller).let { if (it is Verdict.Denied) return ToolResult.denied(it) }
        val heard = ctx.onServer { server ->
            val named = audience.named?.let { refs -> refs.mapNotNull { server.playerList.getPlayer(it.uuid) } }
            if (named != null && named.isEmpty()) return@onServer emptyList()
            Speech.say(server, ctx.services.bus, ctx.settings.ui, text, named, NarratorChannel.NARRATOR, ctx.caller.turnId)
        }
        if (heard.isEmpty()) return ToolResult.failed(nobodyLeft(audience))
        val note = cleaningNote(args.text, Speech.CHAT_LINES)
        return if (audience.named == null) {
            ToolResult.ok("ok: said to everyone (${heard.size} online)$note", heard)
        } else {
            ToolResult.ok("ok: whispered to ${names(heard)}$note", heard)
        }
    }

    /**
     * The refusal of a line of the Director that opens like too many of its latest (M6 §3.5): it says which lines and every
     * opener at rest, so that the line is written again in the same turn. Only in a turn of the Director (neither the
     * engine's acts, a rule's or a module's lines, nor `/narrator say`), at most once a turn (the next line of the turn
     * is not checked: a formula the Director wants passes at the second try), and not with `style.refuseRestingOpeners`
     * off. Nothing is said nor counted by the limits. A decision `style opener_refused`.
     */
    private fun restingOpener(ctx: ToolContext, text: String): ToolResult? {
        val caller = ctx.caller
        val turn = caller.turnId ?: return null
        if (caller.role != Role.DIRECTOR || caller.firingId != null || turn == refusedTurn) return null
        if (!ctx.settings.config.style.refuseRestingOpeners) return null
        val opener = ctx.services.style.restingOpener(text) ?: return null
        refusedTurn = turn
        val others = ctx.services.style.atRest().openers.filterNot { it.opener.equals(opener.opener, ignoreCase = true) }.take(OPENERS_CITED - 1)
        val forms = (listOf(opener.opener) + opener.examples).take(FORMS_CITED).joinToString(", ") { "« ${quoted(it)} »" }
        val also = if (others.isEmpty()) "" else "; the other openers at rest: " + others.joinToString(", ") { "« ${quoted(it.opener)} » ×${it.count}" }
        Decisions(ctx.services.trace, ctx.clock).record(
            Log.style, DecisionRecord.Kind.STYLE, "opener_refused", subject = "« ${opener.opener} »",
            reason = "the line opens like ${opener.count} of the latest", conversationId = caller.conversationId, turnId = turn,
            detail = buildJsonObject {
                put("opener", opener.opener)
                put("count", opener.count)
                put("text", text)
                put("at_rest", JsonArray(others.map { JsonPrimitive(it.opener) }))
            },
        )
        return ToolResult.invalid("this line opens like ${opener.count} of your latest lines ($forms): open another way$also")
    }

    private companion object {
        /** The openers at rest a refusal cites, the one refused first. */
        const val OPENERS_CITED = 5

        /** The forms of the refused opener it cites. */
        const val FORMS_CITED = 3

        /** One of the Director's own lines in a refusal: escaped, its « » turned into ‹ ›. */
        fun quoted(text: String): String = ModelText.escape(text).replace('«', '‹').replace('»', '›')
    }
}

// ── show_title ──────────────────────────────────────────────────────────────────────────────────

@Serializable
data class ShowTitleArgs(
    @Doc("The big title: a few words.")
    @MaxLen(40)
    val title: String,
    @Doc("A smaller line under the title.")
    @MaxLen(80)
    val subtitle: String? = null,
    @Doc(TO_DOC)
    @Items(1, 8)
    @MaxLen(16)
    val to: List<String>? = null,
    @Doc("How long the title stays on screen, in seconds.")
    @Range(1.0, 10.0)
    val seconds: Int = 3,
)

class ShowTitleTool(private val limiter: SpeechLimiter) : NarratorTool<ShowTitleArgs>(
    name = "show_title",
    description = "Shows a big title in the middle of the screen of some players, or of everyone online, with an optional " +
        "subtitle under it. Keep it for big moments: one title per player every 30 seconds.",
    args = ShowTitleArgs.serializer(),
    effect = ToolEffect.SPEECH,
) {
    override suspend fun check(ctx: ToolContext, args: ShowTitleArgs): ToolResult? =
        if (Speech.clean(args.title).isEmpty()) ToolResult.invalid(nothingLeft("title")) else null

    override suspend fun run(ctx: ToolContext, args: ShowTitleArgs): ToolResult {
        check(ctx, args)?.let { return it }
        val title = Speech.clean(args.title)
        val subtitle = args.subtitle?.let { Speech.clean(it) }?.ifEmpty { null }
        val audience = audience(ctx, args.to)
        audience.problem?.let { return it }
        val split = limiter.titles(audience.named ?: ctx.players.online.map { it.ref })
        if (split.allowed.isEmpty()) return waitDenied("title", SpeechLimiter.TITLE_COOLDOWN_MS, split)
        val record = "[title] $title" + (subtitle?.let { " / $it" } ?: "")
        val shown = ctx.onServer { server ->
            deliver(server, ctx, record, split, everyone = audience.named == null) { player ->
                player.connection.send(ClientboundSetTitlesAnimationPacket(TITLE_FADE_IN, args.seconds * TICKS_PER_SECOND, TITLE_FADE_OUT))
                // Always send a subtitle, even an empty one: the title packet shows the last subtitle the client got.
                player.connection.send(ClientboundSetSubtitleTextPacket(subtitle?.let { Component.literal(it) } ?: Component.empty()))
                player.connection.send(ClientboundSetTitleTextPacket(Speech.colored(ctx.settings.ui, title)))
            }
        }
        if (shown.isEmpty()) return ToolResult.failed("nobody got it: ${names(split.allowed)} left the game")
        return ToolResult.ok("ok: title shown to ${whom(shown, audience, split)}${skipped("title", SpeechLimiter.TITLE_COOLDOWN_MS, split)}", shown)
    }

    private companion object {
        const val TITLE_FADE_IN = 10
        const val TITLE_FADE_OUT = 20
        const val TICKS_PER_SECOND = 20
    }
}

// ── actionbar ───────────────────────────────────────────────────────────────────────────────────

@Serializable
data class ActionbarArgs(
    @Doc("A short line shown above the hotbar for a few seconds.")
    @MaxLen(100)
    val text: String,
    @Doc(TO_DOC)
    @Items(1, 8)
    @MaxLen(16)
    val to: List<String>? = null,
)

class ActionbarTool(private val limiter: SpeechLimiter) : NarratorTool<ActionbarArgs>(
    name = "actionbar",
    description = "Shows a short line above the hotbar of some players, or of everyone online, for a few seconds: " +
        "a fleeting aside. One per player every 5 seconds.",
    args = ActionbarArgs.serializer(),
    effect = ToolEffect.SPEECH,
) {
    override suspend fun check(ctx: ToolContext, args: ActionbarArgs): ToolResult? =
        if (Speech.clean(args.text).isEmpty()) ToolResult.invalid(nothingLeft("text")) else null

    override suspend fun run(ctx: ToolContext, args: ActionbarArgs): ToolResult {
        check(ctx, args)?.let { return it }
        val text = Speech.clean(args.text)
        val audience = audience(ctx, args.to)
        audience.problem?.let { return it }
        val split = limiter.actionbars(audience.named ?: ctx.players.online.map { it.ref })
        if (split.allowed.isEmpty()) return waitDenied("line above the hotbar", SpeechLimiter.ACTIONBAR_COOLDOWN_MS, split)
        val shown = ctx.onServer { server ->
            val line = Speech.colored(ctx.settings.ui, text)
            deliver(server, ctx, "[actionbar] $text", split, everyone = audience.named == null) { it.sendOverlayMessage(line) }
        }
        if (shown.isEmpty()) return ToolResult.failed("nobody got it: ${names(split.allowed)} left the game")
        val skipped = skipped("line above the hotbar", SpeechLimiter.ACTIONBAR_COOLDOWN_MS, split)
        return ToolResult.ok("ok: shown to ${whom(shown, audience, split)}$skipped", shown)
    }
}

// ── play_sound ──────────────────────────────────────────────────────────────────────────────────

@Serializable
data class PlaySoundArgs(
    @Doc(
        "Sound event id, e.g. entity.lightning_bolt.thunder, block.bell.use, entity.villager.no, " +
            "ui.toast.challenge_complete; the minecraft: namespace is optional.",
    )
    @MaxLen(100)
    val sound: String,
    @Doc(TO_DOC)
    @Items(1, 8)
    @MaxLen(16)
    val to: List<String>? = null,
    @Doc("Loudness; above 1 the sound carries farther.")
    @Range(0.1, 2.0)
    val volume: Double = 1.0,
    @Doc("1 is normal; lower is deeper and slower, higher is sharper.")
    @Range(0.5, 2.0)
    val pitch: Double = 1.0,
    @Doc(
        "true: only these players hear it, at their own position. " +
            "false: a sound of the world at each player's position, heard by anyone nearby too.",
    )
    @SerialName("private")
    val privately: Boolean = true,
)

class PlaySoundTool(private val limiter: SpeechLimiter) : NarratorTool<PlaySoundArgs>(
    name = "play_sound",
    description = "Plays a Minecraft sound to some players, or to everyone online: privately (the default) or as a sound " +
        "of the world around them. At most 10 sounds per minute.",
    args = PlaySoundArgs.serializer(),
    effect = ToolEffect.SPEECH,
) {
    override suspend fun check(ctx: ToolContext, args: PlaySoundArgs): ToolResult? =
        if (sound(args.sound) == null) ToolResult.invalid(unknownSound(args.sound)) else null

    override suspend fun run(ctx: ToolContext, args: PlaySoundArgs): ToolResult {
        check(ctx, args)?.let { return it }
        val sound = sound(args.sound)!!
        val id = sound.key().identifier()
        val shortId = if (id.namespace == Identifier.DEFAULT_NAMESPACE) id.path else id.toString()
        val audience = audience(ctx, args.to)
        audience.problem?.let { return it }
        limiter.sound().let { if (it is Verdict.Denied) return ToolResult.denied(it) }
        val volume = args.volume.toFloat()
        val pitch = args.pitch.toFloat()
        val heard = ctx.onServer { server ->
            val players = audience.named?.mapNotNull { server.playerList.getPlayer(it.uuid) } ?: server.playerList.players
            for (player in players) {
                if (args.privately) {
                    player.connection.send(
                        ClientboundSoundPacket(sound, SoundSource.MASTER, player.x, player.y, player.z, volume, pitch, player.random.nextLong()),
                    )
                } else {
                    player.level().playSound(null, player.x, player.y, player.z, sound, SoundSource.MASTER, volume, pitch)
                }
            }
            players.map { it.ref() }
        }
        if (heard.isEmpty()) return ToolResult.failed(nobodyLeft(audience))
        val how = if (args.privately) "privately" else "around them"
        val whom = if (audience.named == null) "everyone (${heard.size} online)" else names(heard)
        return ToolResult.ok("ok: $shortId played for $whom, $how", heard)
    }

    /** The sound event called [raw] (`block.bell.use`, `minecraft:block.bell.use`); null when there is none. Any thread. */
    private fun sound(raw: String): Holder.Reference<SoundEvent>? =
        Identifier.tryParse(raw.trim().lowercase())?.let { BuiltInRegistries.SOUND_EVENT.get(it).orElse(null) }

    private fun unknownSound(raw: String): String {
        val ids = BuiltInRegistries.SOUND_EVENT.keySet().map { if (it.namespace == Identifier.DEFAULT_NAMESPACE) it.path else it.toString() }
        return "unknown sound \"$raw\"; closest ids: ${closestIds(raw, ids).joinToString()}"
    }
}

/**
 * The [max] ids of [ids] closest to [input], for an error message: first those that contain the most
 * words of [input] (3 letters or more), then by edit distance to it.
 */
internal fun closestIds(input: String, ids: Collection<String>, max: Int = 5): List<String> {
    val wanted = input.trim().lowercase().removePrefix("${Identifier.DEFAULT_NAMESPACE}:")
    val words = wanted.split(NON_WORD).filter { it.length >= 3 }
    return ids.map { id -> Candidate(id, words.count { it in id }, editDistance(wanted, id)) }
        .sortedWith(compareByDescending<Candidate> { it.words }.thenBy { it.distance }.thenBy { it.id })
        .take(max)
        .map { it.id }
}

/** An id and how close it is to what was asked: words of the request it contains, edit distance. */
private class Candidate(val id: String, val words: Int, val distance: Int)

private val NON_WORD = Regex("[^a-z0-9]+")

/** Levenshtein distance between [a] and [b]. */
private fun editDistance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
        }
        previous = current.also { current = previous }
    }
    return previous[b.length]
}

// ── give_book ───────────────────────────────────────────────────────────────────────────────────

@Serializable
data class GiveBookArgs(
    @Doc("Name of the online player who gets the book.")
    @MaxLen(16)
    val to: String,
    @Doc("Title on the cover.")
    @MaxLen(32)
    val title: String,
    @Doc("The pages, in plain text; newlines are kept, and a page too long for the book continues on the next one.")
    @Items(1, 10)
    @MaxLen(600)
    val pages: List<String>,
)

class GiveBookTool(private val limiter: SpeechLimiter) : NarratorTool<GiveBookArgs>(
    name = "give_book",
    description = "Gives a written book signed by the Narrator to a player: the one place for a long text (a prophecy, " +
        "a contract, a fine, a diploma). One book per player every 5 minutes.",
    args = GiveBookArgs.serializer(),
    effect = ToolEffect.SPEECH,
) {
    override suspend fun run(ctx: ToolContext, args: GiveBookArgs): ToolResult {
        val title = Speech.fit(Speech.clean(args.title), Speech.BOOK_TITLE_MAX)
        if (title.isEmpty()) return ToolResult.invalid(nothingLeft("title"))
        val pages = Speech.layOut(args.pages)
        if (pages.isEmpty()) return ToolResult.invalid(nothingLeft("pages"))
        val audience = audience(ctx, listOf(args.to))
        audience.problem?.let { return it }
        val split = limiter.books(audience.named.orEmpty())
        if (split.allowed.isEmpty()) return waitDenied("book", SpeechLimiter.BOOK_COOLDOWN_MS, split)
        val record = "[book \"$title\"] " + args.pages.map { Speech.clean(it) }.filter { it.isNotEmpty() }.joinToString(" / ")
        val dropped = ctx.onServer { server ->
            val player = server.playerList.getPlayer(split.allowed.single().uuid) ?: return@onServer null
            ctx.spoke(record, listOf(player.ref()))
            give(player, Speech.book(ctx.settings.ui, title, pages, ctx.caller.turnId))
        } ?: return ToolResult.failed("${ModelText.name(args.to)} left the game")
        val who = split.allowed.single()
        val where = if (dropped) "dropped at ${ModelText.name(who.name)}'s feet (inventory full)" else "given to ${ModelText.name(who.name)}"
        return ToolResult.ok("ok: book \"$title\" $where, ${pages.size} pages", listOf(who))
    }

    /** Puts [book] in [player]'s inventory, or at their feet when it is full, as `/give` does. Returns true when dropped. */
    private fun give(player: ServerPlayer, book: ItemStack): Boolean {
        val rest = book.copy()
        ItemLedger.insert(player.inventory, rest)
        val dropped = !rest.isEmpty
        if (dropped) {
            player.createItemStackToDrop(rest, false, false)?.let { item ->
                item.setNoPickUpDelay()
                item.setTarget(player.uuid)
                player.level().addFreshEntity(item)
            }
        } else {
            val pitch = ((player.random.nextFloat() - player.random.nextFloat()) * 0.7f + 1f) * 2f
            player.level().playSound(null, player.x, player.y, player.z, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2f, pitch)
        }
        player.containerMenu.broadcastChanges()
        return dropped
    }
}

// ── Shared ──────────────────────────────────────────────────────────────────────────────────────

/**
 * Who a call addresses: the players [named] (without repeats), or everyone online when null.
 * [problem] is the answer when nobody can be addressed: a name not online, or nobody online at all.
 */
private class Audience(val named: List<PlayerRef>?, val problem: ToolResult?)

private fun audience(ctx: ToolContext, names: List<String>?): Audience {
    val online = ctx.players.online
    if (names == null) return Audience(null, if (online.isEmpty()) ToolResult.failed("nobody is online") else null)
    val found = names.map { it to ctx.players.byName(it) }
    val missing = found.filter { it.second == null }.map { it.first }
    if (missing.isNotEmpty()) return Audience(null, ctx.notOnline(missing))
    return Audience(found.mapNotNull { it.second?.ref }.distinctBy { it.uuid }, null)
}

/**
 * Journals [record] as said to [split]'s allowed players (to everyone when [everyone] and nobody had
 * to wait), then shows it to those still online with [show]. Server thread. Returns who got it.
 */
private fun deliver(
    server: MinecraftServer,
    ctx: ToolContext,
    record: String,
    split: SpeechLimiter.Split,
    everyone: Boolean,
    show: (ServerPlayer) -> Unit,
): List<PlayerRef> {
    val players = split.allowed.mapNotNull { server.playerList.getPlayer(it.uuid) }
    if (players.isEmpty()) return emptyList()
    val refs = players.map { it.ref() }
    ctx.spoke(record, if (everyone && split.waiting.isEmpty()) null else refs)
    players.forEach(show)
    return refs
}

/** Publishes a line of the Narrator ([NarratorSpoke]); `null` recipients: everyone. */
private fun ToolContext.spoke(text: String, recipients: List<PlayerRef>?) {
    services.bus.publish(NarratorSpoke(NarratorChannel.NARRATOR, text, recipients, caller.turnId, operator = null))
}

private fun names(players: List<PlayerRef>): String = ToolText.names(players)

/** Why nobody got a call's text or sound: everyone it named left the game between the checks and the delivery. */
private fun nobodyLeft(audience: Audience): String =
    if (audience.named == null) "nobody is online" else "nobody got it: ${names(audience.named)} left the game"

/** `everyone (3 online)` when the call addressed everyone and everyone got it, else the names. */
private fun whom(shown: List<PlayerRef>, audience: Audience, split: SpeechLimiter.Split): String =
    if (audience.named == null && split.waiting.isEmpty()) "everyone (${shown.size} online)" else names(shown)

/** The refusal when every player addressed must wait for [what]. */
private fun waitDenied(what: String, cooldownMs: Long, split: SpeechLimiter.Split): ToolResult =
    ToolResult.denied(SpeechLimiter.RULE, "one $what per player every ${ToolText.duration(cooldownMs)}: ${split.waiting.joinToString("; ") { it.describe() }}")

/** What an allowed call did not do, for the players who must wait; empty when nobody waits. */
private fun skipped(what: String, cooldownMs: Long, split: SpeechLimiter.Split): String =
    if (split.waiting.isEmpty()) {
        ""
    } else {
        "; skipped (one $what per player every ${ToolText.duration(cooldownMs)}): ${split.waiting.joinToString("; ") { it.describe() }}"
    }

/** The problem of a text that is empty once cleaned. */
private fun nothingLeft(argument: String) =
    "$argument has nothing to show once formatting codes and invisible characters are removed; write plain text"

/** What cleaning changed in a `say` text that the model should know, as ` (…)`; empty when nothing notable. */
private fun cleaningNote(original: String, maxLines: Int): String {
    val notes = buildList {
        if ('§' in original) add("§ formatting codes were removed: text is plain")
        if (original.lines().count { it.isNotBlank() } > maxLines) add("only $maxLines lines are shown, the rest joined the last one")
    }
    return if (notes.isEmpty()) "" else " (${notes.joinToString("; ")})"
}
