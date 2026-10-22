package io.github.clemsix6.narrator2.tool.speech

import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.observer.ref
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.TextColor
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.Filterable
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.WrittenBookContent

/**
 * How the Narrator's words look in the game, whoever wrote them (the Director's speech tools, an
 * operator's `/narrator say`): plain text only, a recognizable prefix and color from [UiTexts], lines
 * and book pages laid out so that nothing is cut or garbled, titles short enough for the screen and
 * left there long enough to be read (M7-TITLES).
 *
 * Every text goes through [clean]: the model and the players can write anything, and a `§` code would
 * recolor or scramble the line on every client (the client reads `§` codes inside plain text).
 * Components are always literal, so a text that looks like component JSON is shown as written.
 */
object Speech {
    /** Most lines a chat line of the Narrator may have. */
    const val CHAT_LINES = 3

    /** Most pages a book may have once laid out (the book screen's own limit is far above what the tools produce). */
    const val BOOK_MAX_PAGES = 100

    /** Longest book title, in UTF-16 units: the item codec and the network refuse longer ones. */
    const val BOOK_TITLE_MAX = 32

    /** Key of the custom data that marks what the Narrator gave (`{narrator2: {kind, turn}}`). */
    const val CUSTOM_DATA_KEY = "narrator2"

    /**
     * Longest title, in characters. The client draws a title 4 times as big as the chat: on a 1920×1080 screen at the
     * automatic GUI scale (4), 480 GUI pixels wide, that leaves 120 pixels of the font. A letter takes 6 of them, capital
     * and accented ones too ([advance]: `i`, `l`, `t` and a space less, `«`, `»` 7, `œ` 10), so 20 letters fill the whole
     * width; 18 leave room for a window's borders and a few wide glyphs.
     */
    const val TITLE_MAX = 18

    /** Longest subtitle, in characters: drawn twice as big as the chat, 240 pixels of the font, 40 letters; 36 leave the same room. */
    const val SUBTITLE_MAX = 36

    /**
     * Longest line above the hotbar, in characters. At the chat's size the screen would hold 80 letters, but the client
     * keeps the line 3 seconds (the last one fading), whatever the server says: about what a glance reads in that time.
     */
    const val ACTIONBAR_MAX = 40

    /** A title's fade-in, in ticks. */
    const val TITLE_FADE_IN = 10

    /** A title's fade-out, in ticks. */
    const val TITLE_FADE_OUT = 20

    /** Seconds a title stays fully shown, whatever its length: the time to notice it. */
    private const val SEEN_SECONDS = 3

    /** Characters read in a second: a title stays one more second per this many characters begun. */
    private const val READ_PER_SECOND = 15

    private const val TICKS_PER_SECOND = 20
    private const val MS_PER_TICK = 50L

    private val FORMATTING_CODE = Regex("§[0-9a-fk-orA-FK-OR]")

    // ── Text ────────────────────────────────────────────────────────────────────────────────────

    /**
     * [text] as plain text that shows the same on every client:
     * - `§` formatting codes are removed (the code and its letter), then any `§` left;
     * - control, format (bidirectional overrides, zero-width), private-use and unpaired surrogate
     *   characters are removed; tabs and Unicode spaces become plain spaces, runs of spaces one space;
     * - line breaks (`\r\n`, `\r`, U+2028, U+2029) become `\n`; lines are trimmed; empty lines are
     *   dropped, or kept one at a time between paragraphs when [keepBlankLines];
     * - beyond [maxLines] lines, the rest joins the last line, separated by spaces.
     *
     * The result may be empty. Cleaning a cleaned text changes nothing.
     */
    fun clean(text: String, maxLines: Int = 1, keepBlankLines: Boolean = false): String {
        require(maxLines >= 1) { "maxLines must be at least 1" }
        val unified = text.replace("\r\n", "\n").replace('\r', '\n').replace(' ', '\n').replace(' ', '\n')
        val visible = buildString {
            unified.codePoints().forEach { cp ->
                when {
                    cp == '\n'.code -> append('\n')
                    cp == '\t'.code || Character.getType(cp) == Character.SPACE_SEPARATOR.toInt() -> append(' ')
                    Character.getType(cp) in HIDDEN -> Unit
                    else -> appendCodePoint(cp)
                }
            }
        }
        val plain = visible.replace(FORMATTING_CODE, "").replace("§", "")
        val trimmed = plain.split('\n').map { it.replace(SPACES, " ").trim() }
        val lines = if (keepBlankLines) {
            trimmed.filterIndexed { i, line -> line.isNotEmpty() || (i > 0 && trimmed[i - 1].isNotEmpty()) }
                .dropWhile { it.isEmpty() }
                .dropLastWhile { it.isEmpty() }
        } else {
            trimmed.filter { it.isNotEmpty() }
        }
        if (lines.size <= maxLines) return lines.joinToString("\n")
        val last = lines.drop(maxLines - 1).filter { it.isNotEmpty() }.joinToString(" ")
        return (lines.take(maxLines - 1) + last).joinToString("\n")
    }

    /** Character types never shown: controls, format characters, private use, lone surrogates, unassigned. */
    private val HIDDEN = setOf(
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.PRIVATE_USE.toInt(),
        Character.SURROGATE.toInt(),
        Character.UNASSIGNED.toInt(),
    )

    private val SPACES = Regex(" {2,}")

    /** [text] cut to [max] UTF-16 units, never between the two halves of a character. */
    fun fit(text: String, max: Int): String {
        if (text.length <= max) return text
        val end = if (text[max - 1].isHighSurrogate()) max - 1 else max
        return text.take(end).trimEnd()
    }

    // ── Components ──────────────────────────────────────────────────────────────────────────────

    /** The Narrator's color (`narrator.color`, validated when the texts were loaded; gold should it not parse). */
    fun color(ui: UiTexts): TextColor = TextColor.parseColor(ui["narrator.color"]).result().orElseGet { TextColor.fromRgb(GOLD) }

    private const val GOLD = 0xFFAA00

    /**
     * A chat line of the Narrator: its prefix in its color, then [text]. A [private] line has the
     * `narrator.whisper_prefix` and a gray italic text, like a whisper; a public one the
     * `narrator.prefix` and a plain text.
     */
    fun chatLine(ui: UiTexts, text: String, private: Boolean): Component {
        val prefix = ui[if (private) "narrator.whisper_prefix" else "narrator.prefix"]
        val body = Component.literal(clean(text, CHAT_LINES))
        if (private) body.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
        return Component.empty()
            .append(colored(ui, prefix))
            .append(" ")
            .append(body)
    }

    /** [text] on one line, in the Narrator's color: a title or a line above the hotbar. */
    fun colored(ui: UiTexts, text: String): MutableComponent {
        val color = color(ui)
        return Component.literal(text).withStyle { it.withColor(color) }
    }

    // ── Titles ──────────────────────────────────────────────────────────────────────────────────

    /** Characters of [text] as the players see them: code points, not UTF-16 units. */
    fun length(text: String): Int = text.codePointCount(0, text.length)

    /**
     * Ticks a title of [chars] characters (title and subtitle together) stays fully shown, between its fades:
     * [SEEN_SECONDS], plus a second per [READ_PER_SECOND] characters begun. 18 + 36 characters: 7 s.
     */
    fun titleStay(chars: Int): Int = (SEEN_SECONDS + (chars + READ_PER_SECOND - 1) / READ_PER_SECOND) * TICKS_PER_SECOND

    /** Milliseconds a title that stays [stay] ticks is on the screen, fades included. */
    fun titleOnScreenMs(stay: Int): Long = (TITLE_FADE_IN + stay + TITLE_FADE_OUT) * MS_PER_TICK

    /**
     * Shows [player] [title] in the Narrator's color with [subtitle] under it (none when null), fully shown for [stay]
     * ticks between the fades. The subtitle goes first, even an empty one: the title packet shows the last subtitle the
     * client got. Server thread.
     */
    fun showTitle(player: ServerPlayer, ui: UiTexts, title: String, subtitle: String?, stay: Int) {
        player.connection.send(ClientboundSetTitlesAnimationPacket(TITLE_FADE_IN, stay, TITLE_FADE_OUT))
        player.connection.send(ClientboundSetSubtitleTextPacket(subtitle?.let { Component.literal(it) } ?: Component.empty()))
        player.connection.send(ClientboundSetTitleTextPacket(colored(ui, title)))
    }

    // ── Chat ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Says [text] in the chat as the Narrator, on the server thread: to every player online with the
     * public prefix (the server console gets it too) when [to] is null, else privately to each of [to].
     *
     * The line is journaled first ([NarratorSpoke], which the `chat` table keeps: it is how the
     * Narrator remembers what it said), then shown in the same tick: a subscriber that clears the
     * "thinking" indicator of the recipients runs before the line reaches them.
     *
     * @param text cleaned here too; must not be empty once cleaned.
     * @return the players who got the line.
     */
    fun say(
        server: MinecraftServer,
        bus: EventBus,
        ui: UiTexts,
        text: String,
        to: List<ServerPlayer>?,
        channel: NarratorChannel,
        turnId: Long?,
        operator: String? = null,
    ): List<PlayerRef> {
        val line = clean(text, CHAT_LINES)
        require(line.isNotEmpty()) { "nothing to say once the text is cleaned" }
        val recipients = to?.distinctBy { it.uuid }
        require(recipients == null || recipients.isNotEmpty()) { "no recipient" }
        bus.publish(NarratorSpoke(channel, line, recipients?.map { it.ref() }, turnId, operator))
        if (recipients == null) {
            server.playerList.broadcastSystemMessage(chatLine(ui, line, private = false), false)
            return server.playerList.players.map { it.ref() }
        }
        val component = chatLine(ui, line, private = true)
        recipients.forEach { it.sendSystemMessage(component) }
        return recipients.map { it.ref() }
    }

    // ── Books ───────────────────────────────────────────────────────────────────────────────────

    /**
     * A written book signed `book.author`, with [title] (cleaned, cut to [BOOK_TITLE_MAX]) and
     * [pages] as [layOut] makes them, marked with custom data `{narrator2: {kind: "book", turn: <turnId>}}`.
     */
    fun book(ui: UiTexts, title: String, pages: List<String>, turnId: Long?): ItemStack {
        val content = WrittenBookContent(
            Filterable.passThrough(fit(clean(title), BOOK_TITLE_MAX)),
            ui["book.author"],
            0,
            pages.map { Filterable.passThrough(Component.literal(it)) },
            true, // plain text: nothing to resolve when the book is opened
        )
        val stack = ItemStack(Items.WRITTEN_BOOK)
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, content)
        val mark = CompoundTag()
        mark.putString("kind", "book")
        turnId?.let { mark.putLong("turn", it) }
        val data = CompoundTag()
        data.put(CUSTOM_DATA_KEY, mark)
        CustomData.set(DataComponents.CUSTOM_DATA, stack, data)
        return stack
    }

    /**
     * The book pages that show [pages] whole. The book screen has no scroll: it shows 14 lines of
     * 114 pixels and hides the rest. Each page is cleaned (newlines kept), wrapped at spaces like the
     * client does, and continues on as many pages as it needs. Glyph widths are those of the default
     * font, rounded up for anything unsure, so the client never wraps more than this does. At most
     * [BOOK_MAX_PAGES] pages.
     */
    fun layOut(pages: List<String>): List<String> = pages
        .map { clean(it, maxLines = Int.MAX_VALUE, keepBlankLines = true) }
        .filter { it.isNotEmpty() }
        .flatMap { page ->
            page.split('\n')
                .flatMap(::wrap)
                .chunked(BOOK_LINES)
                .map { lines -> lines.mapIndexed { i, line -> if (i == 0) line.text else line.before + line.text }.joinToString("") }
                .map { it.trim('\n', ' ') }
                .filter { it.isNotEmpty() }
        }
        .take(BOOK_MAX_PAGES)

    /** Lines a book page shows. */
    private const val BOOK_LINES = 14

    /** Width of a book page's text, in pixels of the default font. */
    private const val BOOK_WIDTH = 114

    /** A wrapped line of a book page, and what separated it from the line before in the text. */
    private class Line(val text: String, val before: String)

    /** One paragraph, wrapped at spaces to [BOOK_WIDTH]; a word wider than a line is cut. */
    private fun wrap(paragraph: String): List<Line> {
        val lines = mutableListOf<Line>()
        val line = StringBuilder()
        var width = 0
        var before = "\n"
        fun flush(next: String) {
            lines += Line(line.toString(), before)
            line.clear()
            width = 0
            before = next
        }
        for (word in paragraph.split(' ').filter { it.isNotEmpty() }) {
            val wordWidth = word.codePoints().map(::advance).sum()
            if (line.isNotEmpty() && width + advance(' '.code) + wordWidth > BOOK_WIDTH) flush(" ")
            if (line.isNotEmpty()) {
                line.append(' ')
                width += advance(' '.code)
            }
            if (width + wordWidth <= BOOK_WIDTH) {
                line.append(word)
                width += wordWidth
                continue
            }
            word.codePoints().forEach { cp ->
                if (line.isNotEmpty() && width + advance(cp) > BOOK_WIDTH) flush("")
                line.appendCodePoint(cp)
                width += advance(cp)
            }
        }
        flush("\n")
        return lines
    }

    /** Horizontal advance of a character in the default font, in pixels (glyph and spacing). */
    private fun advance(cp: Int): Int = when {
        cp >= 0x250 -> 9 // beyond Latin: the wider fallback fonts
        else -> when (cp.toChar()) {
            '!', ',', '.', ':', ';', 'i', '|' -> 2
            '\'', 'l', '`' -> 3
            ' ', 'I', '[', ']', 't' -> 4
            '"', '(', ')', '*', '<', '>', 'f', 'k', '{', '}' -> 5
            '@', '~' -> 7
            else -> 6
        }
    }
}
