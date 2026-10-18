package io.github.clemsix6.narrator2.live

import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.TextBlockParam
import com.fasterxml.jackson.databind.JsonNode
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.BlockPlaced
import io.github.clemsix6.narrator2.event.EntityKilled
import io.github.clemsix6.narrator2.event.EntityRef
import io.github.clemsix6.narrator2.event.PlaceKind
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerRespawned
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.llm.Conversation
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.llm.SendResult
import io.github.clemsix6.narrator2.memory.MemoryLimits
import io.github.clemsix6.narrator2.memory.Quotes
import io.github.clemsix6.narrator2.memory.VoiceGuard
import io.github.clemsix6.narrator2.memory.dream.GrimoireFormat
import io.github.clemsix6.narrator2.reflex.ChatIntent
import io.github.clemsix6.narrator2.reflex.Tone
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.memory.memoryTools
import io.github.clemsix6.narrator2.trace.ConversationRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.test.assertTrue

/**
 * The memory's live acceptance (E7, M3 §19.2, M3-19): a whole evening against the owner's LLMGW, then the
 * night after it. Run by `gradlew liveTest` only; skipped without a key, a tunnel or what is left of the budget.
 *
 * 1. A Journal on a file (`build/live-memory/`), two fake players with three evenings of history behind them,
 *    the real Director, Chronicler, Dreamer and `MemoryService` with the real prompts ([LiveWorld]).
 * 2. **Play**: Bartok insults the Narrator, Mimosa prays and promises three diamants, Bartok dies in lava, the real
 *    Director answers each; an operator says a dated promise in the Narrator's name; both players leave.
 * 3. **Chapter**: an operator closes it; the real summary, then the real nap: a memory `M-`, Bartok's insult, the
 *    operator's promise with its exact words and its due time, the chapter's chronicle.
 * 4. **Dream** asked by an operator, once the server has been empty for 25 minutes (the clock skips them):
 *    traits whose proofs exist, a recap of each player, a session's chronicle, a valid Grimoire.
 * 5. **Sheet**: `player_profile` of Bartok holds his recap, a trait and the insult.
 * 6. **Briefing**: Bartok comes back and speaks; the next chapter's first message holds the Grimoire, Bartok's
 *    sheet (recap and trait), the open promise and a memory.
 *
 * Measured on the way: E3's numbers (the Dreamer's recaps and skills cite numbers of its inputs), E4 (a judge,
 * Opus at effort low, reads 20 texts of the Chronicler and the Dreamer), and M3 §21's open points (tokens of a
 * briefing, cache across memory conversations, cost and duration of a nap and a dream). Assertions are
 * structural; everything goes to `build/reports/liveTest/memory/cycle.md`, for a human to judge the tone.
 */
@Tag("live")
class LiveMemoryCycleTest {
    private val report = StringBuilder("# The memory's live cycle (E7, M3-19)\n\n")
    private val failures = mutableListOf<String>()

    private fun check(ok: Boolean, what: String) {
        report.append("- ${if (ok) "ok" else "**FAILED**"}: $what\n")
        if (!ok) failures += what
    }

    @Test
    fun `an evening, its nap, a dream and the next chapter with the real model`() {
        val key = LiveGateway.key()
        LiveReports.requireBudget(3.0)
        val started = System.nanoTime()
        LiveWorld("e7-cycle", key).use { w ->
            try {
                cycle(w, key)
            } finally {
                val calls = LiveCall.read(w.journal)
                LiveReports.record("LiveMemoryCycleTest", "E7", calls)
                report.append("\n## Every call to Claude\n\n${LiveCall.summary(calls)} · ${(System.nanoTime() - started) / 60_000_000_000} min\n\n")
                    .append(LiveCall.table(calls)).append("\n")
                report.append("World kept in `${w.dir}`.\n")
                LiveReports.write("cycle.md", report.toString())
            }
        }
        assertTrue(failures.isEmpty(), "E7 failed:\n${failures.joinToString("\n")}")
    }

    private fun cycle(w: LiveWorld, key: String) {
        history(w)
        w.join("Bartok")
        w.join("Mimosa")
        runBlocking { w.journal.barrier() }
        w.start()
        Thread.sleep(2_000)

        // 2. Play.
        report.append("## 2. The evening\n\n")
        turn(w, "Bartok's insult") {
            w.chat("Bartok", "narrateur t'es vraiment le dieu le plus nul que j'ai jamais vu, une honte", 0.95, Tone.HOSTILE, ChatIntent.PROVOCATION)
        }
        turn(w, "Mimosa's prayer") { w.pray("Mimosa", "ô grand narrateur, protège-moi des creepers cette nuit et je t'offrirai trois diamants demain, promis") }
        turn(w, "Bartok's death in lava") {
            w.publish(PlayerDied(w.ref("Bartok"), LAVA, "minecraft:lava", null, null, "Bartok tried to swim in lava", 8, false, false))
            Thread.sleep(500)
            w.publish(PlayerRespawned(w.ref("Bartok"), LiveWorld.SPAWN, alive = false))
        }
        val promiseAt = w.clock.instant()
        val promiseLine = w.operatorSays("Clement", PROMISE)
        report.append("- operator Clement said, as the Narrator: « $PROMISE » (e$promiseLine)\n")
        Thread.sleep(1_000)
        turn(w, "the players leave") {
            w.leave("Bartok")
            w.leave("Mimosa")
        }
        w.said.forEach { report.append("- Narrator →${it.to?.joinToString(",") ?: "all"}: « ${it.text} »\n") }
        report.append("\n")

        // 3. Chapter and nap.
        report.append("## 3. Chapter close and nap\n\n")
        val chapter = w.director.status().conversationId ?: error("no chapter opened")
        val firstChapterLines = w.said.size
        val lastBatch = w.rows("SELECT coalesce(max(run_id), 0) FROM memory_runs") { getLong(1) }.single()
        w.director.post(DirectorSignal.CloseChapter(ConversationRecord.EndReason.OPERATOR))
        val summary = awaitSummary(w, chapter)
        report.append("Summary of the chapter:\n\n```\n$summary\n```\n\n")
        val (napRun, napStatus) = w.awaitRun("nap", lastBatch)
        val batches = w.rows("SELECT run_id, trigger, status FROM memory_runs WHERE kind = 'batch' ORDER BY run_id") { "run ${getLong(1)} ${getString(2)} ${getString(3)}" }
        report.append("Batches before the nap: ${batches.ifEmpty { listOf("none") }.joinToString()}\n\n")
        check(napStatus == "done", "the nap is done (run $napRun: $napStatus ${runError(w, napRun)})")
        napChecks(w, chapter, promiseAt, promiseLine)
        runCost(w, napRun, "nap")

        // 4. Dream, once the server has been empty for 25 minutes.
        report.append("\n## 4. Dream\n\n")
        w.clock.offset = Duration.ofMinutes(25)
        val dreamRun = w.memory.jobs.requestDream("operator:Clement").getOrElse { error("no dream: ${it.message}") }
        val (_, dreamStatus) = w.awaitRun("dream", dreamRun - 1, timeoutMs = 30 * 60_000L)
        val trigger = w.rows("SELECT trigger, report FROM memory_runs WHERE run_id = $dreamRun") { "${getString(1)}: ${getString(2)}" }.single()
        report.append("Dream run $dreamRun ($trigger)\n\n")
        check(dreamStatus == "done", "the dream is done (run $dreamRun: $dreamStatus ${runError(w, dreamRun)})")
        dreamChecks(w)
        runCost(w, dreamRun, "dream")

        // 5. Sheet.
        report.append("\n## 5. Bartok's sheet (player_profile)\n\n")
        profileChecks(w)

        // 6. Next chapter.
        report.append("\n## 6. The next chapter\n\n")
        turn(w, "Bartok's return") {
            w.join("Bartok")
            Thread.sleep(300)
            w.chat("Bartok", "narrateur, me revoilà ! tu te souviens de moi ?", 0.94, Tone.MOCKING_PLAYFUL, ChatIntent.QUESTION)
        }
        val next = w.director.status().conversationId ?: error("no chapter after the dream")
        check(next != chapter, "a new chapter opened ($next)")
        briefingChecks(w, next, chapter, key)
        report.append("\nWhat the Narrator said in the new chapter:\n\n")
        w.said.drop(firstChapterLines).forEach { report.append("- →${it.to?.joinToString(",") ?: "all"}: « ${it.text} »\n") }

        // Measures.
        voiceJudge(w, key)
        numbers(w)
        cacheAcrossConversations(w)
    }

    // ── 1. History: three evenings before ───────────────────────────────────────────────────────────

    /** Bartok mines, kills and dies; Mimosa builds; three days and two days ago. No chapter, no Narrator then. */
    private fun history(w: LiveWorld) {
        val today = LocalDate.now(w.zone)
        fun day(daysAgo: Long, time: String): Instant = today.minusDays(daysAgo).atTime(LocalTime.parse(time)).atZone(w.zone).toInstant()
        fun at(time: Instant, block: () -> Unit) = w.at(time, block)
        val bartok = w.ref("Bartok")
        val mimosa = w.ref("Mimosa")
        val mine = Pos("minecraft:overworld", 180, 30, -260)
        val base = Pos("minecraft:overworld", 130, 70, -250)

        at(day(3, "20:10:00")) { w.join("Bartok", mine) }
        at(day(3, "20:12:00")) { w.join("Mimosa", base) }
        at(day(3, "20:15:00")) { w.chat("Mimosa", "bartok tu viens m'aider à construire ?", 0.1, Tone.NEUTRAL_NO_TARGET, ChatIntent.BANTER_BETWEEN_PLAYERS) }
        at(day(3, "20:15:30")) { w.chat("Bartok", "non je mine, débrouille toi", 0.1, Tone.NEUTRAL_NO_TARGET, ChatIntent.BANTER_BETWEEN_PLAYERS) }
        val ores = List(60) { "minecraft:stone" } + List(14) { "minecraft:coal_ore" } + List(9) { "minecraft:iron_ore" } + List(3) { "minecraft:deepslate_diamond_ore" }
        ores.forEachIndexed { i, block ->
            at(day(3, "20:16:00").plusSeconds(i * 18L)) {
                w.publish(BlockBroken(bartok, Pos("minecraft:overworld", 180 + i % 5, if (block.contains("diamond")) -54 else 30 - i / 6, -260), block, "minecraft:iron_pickaxe", false, true, null))
            }
        }
        at(day(3, "20:47:00")) { w.publish(PlayerDied(bartok, LAVA, "minecraft:lava", null, null, "Bartok tried to swim in lava", 5, false, false)) }
        listOf("minecraft:zombie", "minecraft:zombie", "minecraft:zombie", "minecraft:skeleton", "minecraft:zombie", "minecraft:skeleton", "minecraft:zombie").forEachIndexed { i, mob ->
            at(day(3, "20:50:00").plusSeconds(i * 40L)) { w.publish(EntityKilled(bartok, mine, EntityRef(mob, null, null), "minecraft:iron_sword", false, false)) }
        }
        at(day(3, "21:00:00")) { w.publish(PlayerDied(bartok, Pos("minecraft:overworld", 150, 90, -230), "minecraft:fall", null, null, "Bartok fell from a high place", 3, false, false)) }
        at(day(3, "21:05:00")) { w.leave("Bartok") }
        val blocks = List(90) { "minecraft:oak_planks" } + List(40) { "minecraft:glass" } + List(20) { "minecraft:stone_bricks" }
        blocks.forEachIndexed { i, block ->
            at(day(3, "20:20:00").plusSeconds(i * 18L)) {
                w.publish(BlockPlaced(mimosa, Pos("minecraft:overworld", 130 + i % 9, 70 + i / 30, -250 + i % 7), block, block, PlaceKind.BLOCK))
            }
        }
        repeat(3) { i -> at(day(3, "21:08:00").plusSeconds(i * 50L)) { w.publish(EntityKilled(mimosa, base, EntityRef("minecraft:spider", null, null), "minecraft:stone_sword", false, false)) } }
        at(day(3, "21:15:00")) { w.publish(PlayerDied(mimosa, Pos("minecraft:overworld", 140, 58, -260), "minecraft:drown", null, null, "Mimosa drowned", 4, false, false)) }
        at(day(3, "21:30:00")) { w.leave("Mimosa") }

        at(day(2, "21:00:00")) { w.join("Bartok", mine) }
        at(day(2, "21:05:00")) { w.join("Mimosa", base) }
        repeat(25) { i -> at(day(2, "21:06:00").plusSeconds(i * 20L)) { w.publish(BlockBroken(bartok, Pos("minecraft:overworld", 185, 28 - i / 5, -262), "minecraft:stone", "minecraft:iron_pickaxe", false, true, null)) } }
        repeat(2) { i -> at(day(2, "21:20:00").plusSeconds(i * 60L)) { w.publish(EntityKilled(bartok, mine, EntityRef("minecraft:creeper", null, null), "minecraft:iron_sword", false, false)) } }
        at(day(2, "21:30:00")) {
            w.publish(PlayerDied(bartok, mine, "minecraft:explosion", EntityRef("minecraft:creeper", null, null), null, "Bartok was blown up by Creeper", 6, false, false))
        }
        repeat(40) { i ->
            at(day(2, "21:08:00").plusSeconds(i * 25L)) { w.publish(BlockPlaced(mimosa, Pos("minecraft:overworld", 120, 70 + i, -240), "minecraft:cobblestone", "minecraft:cobblestone", PlaceKind.BLOCK)) }
        }
        at(day(2, "21:35:00")) { w.chat("Mimosa", "j'ai fini ma tour !", 0.2, Tone.NEUTRAL_NO_TARGET, ChatIntent.BANTER_BETWEEN_PLAYERS) }
        at(day(2, "21:40:00")) { w.leave("Bartok") }
        at(day(2, "21:45:00")) { w.leave("Mimosa") }
    }

    // ── Steps ───────────────────────────────────────────────────────────────────────────────────────

    /** Runs [action], waits for the Director's turn it causes (30 s at most to start), then for a quiet Director. */
    private fun turn(w: LiveWorld, what: String, action: () -> Unit) {
        val said = w.said.size
        val turns = w.rows("SELECT count(*) FROM turns") { getLong(1) }.single()
        action()
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) {
            if (w.director.status().turnId != null || w.said.size > said) break
            if (w.rows("SELECT count(*) FROM turns") { getLong(1) }.single() > turns) break
            Thread.sleep(250)
        }
        w.awaitQuiet(what)
        val lines = w.said.drop(said)
        println("[live] $what: ${lines.size} line(s)")
    }

    private fun awaitSummary(w: LiveWorld, chapter: String): String {
        val end = System.currentTimeMillis() + 5 * 60_000L
        while (true) {
            val row = w.rows("SELECT ended_at IS NOT NULL, summary FROM conversations WHERE conversation_id = '$chapter'") { getBoolean(1) to getString(2) }.single()
            if (row.first) return row.second ?: "(closed without a summary)"
            check(System.currentTimeMillis() < end) { "the chapter $chapter did not close" }
            Thread.sleep(1_000)
        }
    }

    private fun runError(w: LiveWorld, run: Long): String =
        w.rows("SELECT coalesce(error, '') FROM memory_runs WHERE run_id = $run") { getString(1) }.singleOrNull().orEmpty()

    private fun napChecks(w: LiveWorld, chapter: String, promiseAt: Instant, promiseLine: Long) {
        val memories = w.rows("SELECT id, title, content, importance FROM memories WHERE source = 'chronicler' ORDER BY id") { "M-${getLong(1)} ★${getInt(4)} ${getString(2)}: ${getString(3)}" }
        report.append("Memories of the nap:\n").append(memories.joinToString("") { "- $it\n" }).append("\n")
        check(memories.isNotEmpty(), "at least one memory M- (${memories.size})")

        val interactions = w.rows("SELECT id, player, kind, valence, summary, quote, source, ts FROM interactions ORDER BY id") {
            "I-${getLong(1)} ${getString(2)} ${getString(3)} ${getInt(4)}: ${getString(5)}${getString(6)?.let { q -> " · « $q »" }.orEmpty()} (${getString(7)})"
        }
        report.append("Interactions:\n").append(interactions.joinToString("") { "- $it\n" }).append("\n")
        val insult = w.rows("SELECT count(*) FROM interactions WHERE player = 'Bartok' AND kind IN ('insult', 'provocation') AND NOT archived") { getLong(1) }.single()
        check(insult > 0, "Bartok's insult is an interaction (insult or provocation: $insult)")

        val canon = w.rows("SELECT id, kind, statement, quote, due_at, source, players::VARCHAR, evidence_events::VARCHAR FROM canon ORDER BY id") {
            CanonLine(getLong(1), getString(2), getString(3), getString(4), getObject(5, OffsetDateTime::class.java)?.toInstant(), getString(6), getString(8))
        }
        report.append("Canon:\n").append(canon.joinToString("") { "- C-${it.id} ${it.kind}${it.due?.let { d -> ", due ${d.atZone(w.zone).toLocalTime().withNano(0)}" }.orEmpty()}: ${it.statement} · « ${it.quote} » (${it.source}, evidence ${it.evidence})\n" }).append("\n")
        val promise = canon.firstOrNull { it.kind == "promise" && it.quote != null && Quotes.normalize("bottes dans une heure") in Quotes.normalize(it.quote) }
        check(promise != null, "the operator's promise is canon, with its exact words")
        if (promise != null) {
            check(Quotes.normalize(promise.quote!!) in Quotes.normalize(PROMISE), "its quote is the operator's exact words: « ${promise.quote} »")
            val due = promise.due
            val expected = promiseAt.plus(Duration.ofHours(1))
            check(due != null && Duration.between(expected, due).abs() <= Duration.ofMinutes(10), "its due time is an hour after the line: $due (expected about $expected)")
            val cited = promise.evidence.removeSurrounding("[", "]").split(',').mapNotNull { it.trim().toLongOrNull() }
            if (promise.source == "chronicler") check(promiseLine in cited, "it cites the operator's line e$promiseLine (${promise.evidence})")
            report.append("The promise was recorded by: **${promise.source}**\n\n")
        }
        val chronicle = w.rows("SELECT id, title, source, length(summary) FROM chronicle WHERE level = 'chapter' AND conversation_id = '$chapter'") {
            "H-${getLong(1)} « ${getString(2)} » (${getString(3)}, ${getInt(4)} characters)"
        }
        check(chronicle.isNotEmpty(), "the chapter's chronicle: ${chronicle.joinToString()}")
        val sinceLast = w.rows("SELECT key, content FROM memory_docs WHERE key LIKE '%:since_last' ORDER BY key, version") { "${getString(1)}: ${getString(2)}" }
        report.append("Since last time:\n").append(sinceLast.joinToString("") { "- $it\n" }).append("\n")
    }

    private fun dreamChecks(w: LiveWorld) {
        val traits = w.rows(
            "SELECT p.name, t.trait, t.side, t.label, t.intensity, t.confidence, t.evidence::VARCHAR, t.note FROM player_traits t JOIN players p ON p.uuid = t.player_uuid WHERE NOT t.retired ORDER BY p.name, t.trait",
        ) { listOf(getString(1), getString(2), getInt(3).toString(), getString(4), getInt(5).toString(), "%.2f".format(Locale.ROOT, getDouble(6)), getString(7), getString(8)) }
        report.append("Traits:\n").append(traits.joinToString("") { "- ${it[0]} ${it[1]} side ${it[2]}${it[3]?.let { l -> " « $l »" }.orEmpty()} ${it[4]}/5, confidence ${it[5]}, proofs ${it[6]}: ${it[7]}\n" }).append("\n")
        check(traits.isNotEmpty(), "at least one trait (${traits.size})")
        val proofs = traits.flatMap { trait -> trait[6]!!.removeSurrounding("[", "]").split(',').mapNotNull { it.trim().toLongOrNull() } }.distinct()
        val existing = if (proofs.isEmpty()) 0L else w.rows(
            "SELECT count(*) FROM (SELECT id FROM memories WHERE id IN (${proofs.joinToString()}) UNION SELECT id FROM interactions WHERE id IN (${proofs.joinToString()}))",
        ) { getLong(1) }.single()
        check(proofs.isNotEmpty() && existing == proofs.size.toLong(), "every proof of the traits exists ($existing of ${proofs.size})")

        val skills = w.rows("SELECT p.name, s.skill, s.score, s.rationale FROM player_skills s JOIN players p ON p.uuid = s.player_uuid ORDER BY p.name, s.skill") {
            "${getString(1)} ${getString(2)} ${getInt(3)}/10: ${getString(4)}"
        }
        report.append("Skills:\n").append(skills.joinToString("") { "- $it\n" }).append("\n")
        val docs = w.rows(
            "SELECT d.key, d.version, d.content, d.source FROM memory_docs d WHERE d.version = (SELECT max(version) FROM memory_docs e WHERE e.key = d.key) ORDER BY d.key",
        ) { listOf(getString(1), getInt(2).toString(), getString(3), getString(4)) }
        val names = w.rows("SELECT uuid::VARCHAR, name FROM players") { getString(1) to getString(2) }.toMap()
        fun keyName(key: String) = names.entries.fold(key) { acc, (uuid, name) -> acc.replace(uuid, name) }
        report.append("Documents (latest versions):\n\n")
        docs.filter { it[0] != "grimoire" }.forEach { report.append("- `${keyName(it[0]!!)}` v${it[1]} (${it[3]}): ${it[2]}\n") }
        listOf("Bartok", "Mimosa").forEach { player ->
            check(docs.any { keyName(it[0]!!) == "sheet:$player:recap" }, "a recap of $player")
        }
        val sessions = w.rows("SELECT id, title, summary FROM chronicle WHERE level = 'session' AND superseded_by IS NULL") { "H-${getLong(1)} « ${getString(2)} »: ${getString(3)}" }
        report.append("\nChronicle, sessions and seasons:\n").append(sessions.joinToString("") { "- $it\n" })
        w.rows("SELECT id, level, title, summary FROM chronicle WHERE level IN ('season', 'saga') AND superseded_by IS NULL") { "H-${getLong(1)} ${getString(2)} « ${getString(3)} »: ${getString(4)}" }
            .forEach { report.append("- $it\n") }
        check(sessions.isNotEmpty(), "a session's chronicle")
        val grimoire = docs.firstOrNull { it[0] == "grimoire" }?.get(2)
        report.append("\nGrimoire:\n\n```\n${grimoire ?: "(none)"}\n```\n\n")
        check(grimoire != null, "a Grimoire")
        if (grimoire != null) {
            val problems = GrimoireFormat.problems(grimoire)
            check(problems.isEmpty(), "the Grimoire has its seven sections: $problems")
            check(grimoire.length <= MemoryLimits.GRIMOIRE, "the Grimoire fits its budget (${grimoire.length} of ${MemoryLimits.GRIMOIRE})")
            val voice = VoiceGuard.check(grimoire, "text", MemoryLimits.GRIMOIRE, names.values)
            check(voice == null, "the Grimoire passes VoiceGuard: ${voice ?: "ok"}")
            val warnings = VoiceGuard.warnings(grimoire)
            report.append("Grimoire: ${grimoire.length} characters, VoiceGuard warnings ${warnings.ifEmpty { listOf("none") }}\n\n")
        }
        val notes = w.rows("SELECT id, kind, text, status FROM notes ORDER BY id") { "N-${getLong(1)} ${getString(2)} (${getString(4)}): ${getString(3)}" }
        report.append("Notes:\n").append(notes.joinToString("") { "- $it\n" }).append("\n")
        val dreamMemories = w.rows("SELECT id, kind, title, content FROM memories WHERE source = 'dreamer' ORDER BY id") { "M-${getLong(1)} ${getString(2)} ${getString(3)}: ${getString(4)}" }
        val dreamCanon = w.rows("SELECT id, kind, status, statement FROM canon WHERE source = 'dreamer' ORDER BY id") { "C-${getLong(1)} ${getString(2)} (${getString(3)}): ${getString(4)}" }
        report.append("Written by the Dreamer besides: ${(dreamMemories + dreamCanon).ifEmpty { listOf("nothing") }.joinToString("") { "\n- $it" }}\n\n")
        val audit = w.rows("SELECT reason FROM engine_decisions WHERE kind = 'memory' AND decision = 'voice_audit' ORDER BY id") { getString(1) }
        report.append("Voice audit of the dream: ${audit.ifEmpty { listOf("none") }.joinToString()}\n\n")
    }

    private fun profileChecks(w: LiveWorld) {
        @Suppress("UNCHECKED_CAST")
        val tool = memoryTools(w.services).single { it.name == "player_profile" } as NarratorTool<Any>
        val result = runBlocking {
            val decoded = ToolArgs.decode(tool.args, """{"player": "Bartok"}""")
            check(decoded is ToolArgs.Decoded.Ok) { "player_profile's arguments: $decoded" }
            tool.run(ToolContext(w.services, Caller(Role.DIRECTOR, "director-live-profile", null, null), w.clock, tool.timeoutMs), (decoded as ToolArgs.Decoded.Ok).args)
        }
        report.append("```\n${result.text}\n```\n\n")
        val recap = w.rows(
            "SELECT content FROM memory_docs d JOIN players p ON d.key = 'sheet:' || p.uuid::VARCHAR || ':recap' WHERE p.name = 'Bartok' ORDER BY version DESC LIMIT 1",
        ) { getString(1) }.singleOrNull()
        check(recap != null && recap.take(40) in result.text, "the profile holds Bartok's recap")
        check(Regex("(?m)^Traits").containsMatchIn(result.text) && Regex("(?m)^  \\S.* \\d/5").containsMatchIn(result.text), "the profile holds a trait")
        val insult = w.rows("SELECT id FROM interactions WHERE player = 'Bartok' AND kind IN ('insult', 'provocation') AND NOT archived ORDER BY id LIMIT 1") { getLong(1) }.singleOrNull()
        check(insult != null && "I-$insult " in result.text, "the profile's history holds the insult I-$insult")
    }

    private fun briefingChecks(w: LiveWorld, next: String, previous: String, key: String) {
        val opening = w.firstUserText(next)
        report.append("<details><summary>First message of the next chapter (${opening.length} characters)</summary>\n\n```\n$opening\n```\n</details>\n\n")
        check("<grimoire" in opening && "## Players" in opening, "it holds the Grimoire")
        val recap = w.rows(
            "SELECT content FROM memory_docs d JOIN players p ON d.key = 'sheet:' || p.uuid::VARCHAR || ':recap' WHERE p.name = 'Bartok' ORDER BY version DESC LIMIT 1",
        ) { getString(1) }.singleOrNull()
        check(recap != null && recap.take(40) in opening, "it holds Bartok's recap")
        check(Regex("Traits: [^\\n]*\\d/5").containsMatchIn(opening), "it holds a trait of a sheet")
        val promise = w.rows("SELECT id FROM canon WHERE kind = 'promise' AND status = 'active' ORDER BY id DESC LIMIT 1") { getLong(1) }.singleOrNull()
        check(promise != null && "C-$promise " in opening, "it holds the open promise C-$promise")
        check(Regex("<memories[^>]*>[\\s\\S]*M-\\d+").containsMatchIn(opening), "it holds a memory M-")

        // §21.1: characters and tokens of a chapter's first message.
        val calls = LiveCall.read(w.journal, "conversation_id IN ('$previous', '$next')")
        val first = calls.firstOrNull { it.conversation == next }
        val firstOfPrevious = calls.firstOrNull { it.conversation == previous }
        val briefing = opening.substringBefore("</briefing>") + "</briefing>"
        val counted = LiveGateway.countTokens(key, briefing)
        report.append("\n### M3 §21.1: characters per token of the briefing\n\n")
        if (first != null) {
            report.append("- first call of the new chapter: input ${first.input}, cache read ${first.read}, cache write ${first.write}; first message ${opening.length} characters\n")
            if (first.read > 0) {
                report.append("- the prefix (prompt and tools) came from the cache: the message is ${first.input + first.write} tokens, %.2f characters per token\n".format(Locale.ROOT, opening.length.toDouble() / (first.input + first.write)))
            }
        }
        firstOfPrevious?.let { report.append("- first call of the first chapter: input ${it.input}, cache read ${it.read}, cache write ${it.write}\n") }
        report.append("- count_tokens of the briefing alone (${briefing.length} characters): ${counted?.let { "$it tokens, %.2f characters per token".format(Locale.ROOT, briefing.length.toDouble() / it) } ?: "not relayed by the gateway"}\n\n")
    }

    /** E4 (§12.3): a judge, Opus at effort low, reads up to 20 texts of the Chronicler and the Dreamer. */
    private fun voiceJudge(w: LiveWorld, key: String) {
        report.append("\n## E4 · voice of the memory (judge)\n\n")
        val chronicler = w.rows(
            """
            SELECT 'memory', title || ': ' || content FROM memories WHERE source = 'chronicler'
            UNION ALL SELECT 'interaction', summary FROM interactions WHERE source = 'chronicler'
            UNION ALL SELECT 'canon', statement FROM canon WHERE source = 'chronicler'
            UNION ALL SELECT 'note', text FROM notes WHERE source = 'chronicler'
            UNION ALL SELECT 'since_last', content FROM memory_docs WHERE source = 'chronicler'
            UNION ALL SELECT 'chronicle', summary FROM chronicle WHERE source = 'chronicler'
            """.trimIndent(),
        ) { "chronicler ${getString(1)}" to getString(2) }
        val dreamer = w.rows(
            """
            SELECT 'doc ' || split_part(key, ':', 3), content FROM memory_docs WHERE source = 'dreamer' AND key LIKE 'sheet:%'
            UNION ALL SELECT 'review', content FROM memory_docs WHERE source = 'dreamer' AND key = 'review'
            UNION ALL SELECT 'chronicle ' || level, summary FROM chronicle WHERE source = 'dreamer'
            UNION ALL SELECT 'trait', note FROM player_traits WHERE note IS NOT NULL
            UNION ALL SELECT 'skill', rationale FROM player_skills
            UNION ALL SELECT 'relation', note FROM player_relations
            UNION ALL SELECT 'memory', title || ': ' || content FROM memories WHERE source = 'dreamer'
            UNION ALL SELECT 'grimoire', content FROM memory_docs WHERE key = 'grimoire'
            """.trimIndent(),
        ) { "dreamer ${getString(1)}" to getString(2) }
        val texts = (spread(chronicler, 10) + spread(dreamer, 20 - minOf(10, chronicler.size))).take(20)
        if (texts.isEmpty()) {
            check(false, "E4: no text to judge")
            return
        }
        val numbered = texts.withIndex().joinToString("\n\n") { (i, text) -> "[${i + 1}] (${text.first})\n${text.second}" }
        val conversation = Conversation(Role.ANALYST, "E4 voice judge", listOf(TextBlockParam.builder().text(JUDGE).build()), transcripts = w.transcripts)
        val result = runBlocking { w.client.send(conversation, listOf(MessageParam.builder().role(MessageParam.Role.USER).content(numbered).build())) }
        val stats = result.stats
        val judgeCall = LiveCall(0, "judge", conversation.id, "analyst", stats.effort, Instant.now(), stats.durationMs.toInt(), stats.inputTokens, stats.cacheReadTokens, stats.cacheWriteTokens, stats.outputTokens, stats.stopReason, stats.error)
        LiveReports.record("LiveMemoryCycleTest", "E4 judge", listOf(judgeCall))
        val verdicts = runCatching {
            Json.parseToJsonElement(result.text.substringAfter("[", "").let { "[$it" }.substringBeforeLast("]") + "]") as JsonArray
        }.getOrElse { JsonArray(emptyList()) }
        val byId = verdicts.associate { it.jsonObject.getValue("id").jsonPrimitive.int to it.jsonObject }
        var yes = 0
        texts.forEachIndexed { i, text ->
            val verdict = byId[i + 1]
            val ok = verdict?.get("ok")?.jsonPrimitive?.boolean == true
            if (ok) yes++
            val why = verdict?.get("why")?.jsonPrimitive?.content.orEmpty()
            report.append("- ${if (ok) "yes" else "**no**"} [${i + 1}] ${text.first}: ${text.second.replace("\n", " ⏎ ").take(400)}${if (why.isNotEmpty()) " — judge: $why" else ""}\n")
        }
        val share = yes.toDouble() / texts.size
        report.append("\nE4: $yes of ${texts.size} = %.2f (threshold 0.95) · judge ${judgeCall.input + judgeCall.read + judgeCall.write} tokens in, ${judgeCall.output} out, $%.4f\n\n".format(Locale.ROOT, share, judgeCall.dollars))
        check(result is SendResult.Reply && byId.size == texts.size, "E4: the judge answered for every text (${byId.size} of ${texts.size})")
        check(share >= 0.95, "E4: %.2f of the texts judged factual, third person, not the Narrator's voice (≥ 0.95)".format(Locale.ROOT, share))
    }

    /** At most [n] of [texts], taken in turn from each kind, so that every kind is judged. */
    private fun spread(texts: List<Pair<String, String>>, n: Int): List<Pair<String, String>> {
        val byKind = texts.groupBy { it.first }.values.map { it.toMutableList() }
        val out = mutableListOf<Pair<String, String>>()
        while (out.size < n && byKind.any { it.isNotEmpty() }) byKind.forEach { list -> if (out.size < n && list.isNotEmpty()) out += list.removeAt(0) }
        return out
    }

    /** E3's numbers: those of the Dreamer's recaps and skill rationales must be in what the Dreamer received. */
    private fun numbers(w: LiveWorld) {
        report.append("\n## E3 · numbers of the Dreamer\n\n")
        val dreamers = w.rows("SELECT conversation_id FROM conversations WHERE role = 'dreamer'") { getString(1) }
        val inputs = dreamers.flatMap { id -> w.transcriptMessages(id).filter { it.path("role").asText() == "user" }.map(::allText) }.joinToString("\n")
        val known = NUMBER.findAll(normalizeNumbers(inputs)).map { it.value }.toSet()
        val texts = w.rows("SELECT key, content FROM memory_docs WHERE source = 'dreamer' AND key LIKE 'sheet:%:recap'") { getString(1) to getString(2) } +
            w.rows("SELECT skill, rationale FROM player_skills") { getString(1) to getString(2) }
        var total = 0
        var found = 0
        texts.forEach { (what, text) ->
            val numbers = NUMBER.findAll(normalizeNumbers(text)).map { it.value }.toList()
            val missing = numbers.filter { it !in known }
            total += numbers.size
            found += numbers.size - missing.size
            if (numbers.isNotEmpty()) report.append("- ${what.substringAfterLast(':')}: ${numbers.joinToString()}${if (missing.isNotEmpty()) " — **not in the inputs**: ${missing.joinToString()}" else ""}\n")
        }
        val share = if (total == 0) 1.0 else found.toDouble() / total
        report.append("\nE3 numbers: $found of $total found in the Dreamer's inputs = %.2f (threshold 0.95)\n\n".format(Locale.ROOT, share))
        check(share >= 0.95, "E3: %.2f of the numbers of the recaps and skills come from the inputs (≥ 0.95)".format(Locale.ROOT, share))
        val invented = w.rows("SELECT quote FROM canon WHERE source = 'chronicler' AND quote IS NOT NULL") { getString(1) }
            .filter { quote -> w.rows("SELECT text FROM chat WHERE channel IN ('narrator', 'operator')") { getString(1) }.none { Quotes.normalize(quote) in Quotes.normalize(it) } }
        check(invented.isEmpty(), "E3: no canon quote of the Chronicler that the Narrator did not say: $invented")
    }

    /** M3 §21.3 and §21.4: the cache from one memory conversation to the next, and what each job cost. */
    private fun cacheAcrossConversations(w: LiveWorld) {
        report.append("\n## M3 §21.3: cache from one memory conversation to the next\n\n| conversation | label | first call: cache read | cache write | input |\n|---|---|---|---|---|\n")
        val conversations = w.rows("SELECT conversation_id, label FROM conversations WHERE role IN ('chronicler', 'dreamer') ORDER BY started_at, conversation_id") { getString(1) to getString(2) }
        conversations.forEach { (id, label) ->
            val first = LiveCall.read(w.journal, "conversation_id = '$id'").firstOrNull()
            report.append("| $id | $label | ${first?.read} | ${first?.write} | ${first?.input} |\n")
        }
    }

    /** M3 §21.4: what the job [run] cost and how long it took, conversation by conversation. */
    private fun runCost(w: LiveWorld, run: Long, what: String) {
        val row = w.rows("SELECT started_at, ended_at, conversations::VARCHAR, writes, rejected FROM memory_runs WHERE run_id = $run") {
            listOf(getObject(1, OffsetDateTime::class.java).toInstant(), getObject(2, OffsetDateTime::class.java)?.toInstant(), getString(3), getInt(4), getInt(5))
        }.single()
        val conversations = (row[2] as String).removeSurrounding("[", "]").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val calls = w.calls(conversations)
        val took = row[1]?.let { Duration.between(row[0] as Instant, it as Instant) }
        report.append("\n### Cost of the $what (run $run)\n\n${LiveCall.summary(calls)} · ${took?.let { "${it.toMinutes()} min ${it.toSecondsPart()} s" } ?: "?"} · writes ${row[3]} · refused ${row[4]}\n\n")
        val labels = w.rows("SELECT conversation_id, label FROM conversations WHERE role IN ('chronicler', 'dreamer')") { getString(1) to getString(2) }.toMap()
        conversations.forEach { id ->
            val mine = calls.filter { it.conversation == id }
            report.append("- ${labels[id] ?: id}: ${LiveCall.summary(mine)}\n")
        }
    }

    private data class CanonLine(val id: Long, val kind: String, val statement: String, val quote: String?, val due: Instant?, val source: String, val evidence: String)

    private companion object {
        const val PROMISE = "Mimosa, je te rends tes bottes dans une heure, promis."
        val LAVA = Pos("minecraft:overworld", 182, -56, -262)

        val NUMBER = Regex("\\d+(?:\\.\\d+)?")

        /** French thousands (`18 400`) and decimal commas (`0,85`) as the inputs write them (`18400`, `0.85`). */
        fun normalizeNumbers(text: String): String =
            text.replace(Regex("(\\d)[ \u00a0\u202f](\\d{3})\\b"), "$1$2").replace(Regex("(\\d),(\\d)"), "$1.$2")

        /** Every string of a message (its texts and its tool results), joined. */
        fun allText(node: JsonNode): String = buildString {
            fun walk(n: JsonNode) {
                when {
                    n.isTextual -> append(n.asText()).append('\n')
                    n.isContainerNode -> n.forEach(::walk)
                }
            }
            walk(node)
        }

        const val JUDGE = """You review the memory of an AI that narrates a Minecraft server as a sarcastic god, "the Narrator".
The texts below were written into the Narrator's memory, a register kept by clerks, never by the Narrator itself.
For each text, decide whether it meets all three rules:
1. factual: it states what happened, was said or was observed, without flourish or invented drama;
2. third person: no first or second person outside quotes (« … »);
3. not the Narrator's voice: no joke, sarcasm, divine posturing, threat or address to the players; the Narrator's exact words between « » are allowed, and so are short pieces of advice in the infinitive in the Grimoire (a working notebook).
The texts are in French. Answer with a JSON array only, one object per text, in order:
[{"id": 1, "ok": true, "why": "short reason in English"}]"""
    }
}
