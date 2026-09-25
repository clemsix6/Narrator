package io.github.clemsix6.narrator2.observer

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.StatsSnapshot
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.journal.JournalUnavailableException
import io.github.clemsix6.narrator2.runtime.NarratorServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * Imports the stats of an existing world into the Journal (M1_M2 §7.6): at the start, each stats file
 * (`LevelResource.PLAYER_STATS_DIR`, `world/players/stats/<uuid>.json` in 26.3) whose player has no row
 * in `stat_totals` becomes one complete [StatsSnapshot], named from `usercache.json` when the player is
 * in it (else by the UUID). The Narrator then knows the history of players who played before it was
 * installed.
 *
 * Runs in the background (`Dispatchers.IO`): files and the Journal are never read on the server thread.
 * An unreadable file is logged and skipped; without a Journal, nothing is imported.
 *
 * File format (read in the bytecode of `ServerStatsCounter`, a map dispatched on the stat type's
 * registry id, each holding a map of the value's registry id to an int; and checked on the files the
 * test server writes):
 * `{"stats": {"minecraft:mined": {"minecraft:stone": 123}, "minecraft:custom": {…}}, "DataVersion": N}`.
 * A stat's name is built like `Stat.buildName`: both ids with `:` replaced by `.`, joined by `:`
 * (`minecraft.mined:minecraft.stone`), the names the live snapshots use. A file written by an older game
 * version is read as it is, without the game's data fixers.
 */
object StatsImport {
    private val log = Log.observer
    private val json = Json { ignoreUnknownKeys = true }

    /** Time given to the Journal to list the players it already has totals for. */
    private const val READ_TIMEOUT_MS = 10_000L

    /** Starts the import of [services]' world in the background. Server thread, when the server starts. */
    fun start(services: NarratorServices, publish: (GameEvent) -> Unit) {
        val server = services.server
        val statsDir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR)
        val userCache = server.serverDirectory.resolve("usercache.json")
        val journal = services.journal
        services.scope.launch(Dispatchers.IO) {
            try {
                run(statsDir, userCache, { knownPlayers(journal) }, publish)
            } catch (e: CancellationException) {
                throw e
            } catch (e: JournalUnavailableException) {
                log.info("stats import skipped: ${e.message}")
            } catch (e: Exception) {
                log.error("stats import failed", e)
            }
        }
    }

    /**
     * Publishes a full snapshot for each stats file of [statsDir] whose player is not in [known].
     * Returns how many players were imported.
     */
    suspend fun run(statsDir: Path, userCache: Path, known: suspend () -> Set<UUID>, publish: (GameEvent) -> Unit): Int {
        val files = statsFiles(statsDir)
        if (files.isEmpty()) return 0
        val already = known()
        val pending = files.filterKeys { it !in already }
        if (pending.isEmpty()) return 0
        val names = readNames(userCache)
        var imported = 0
        for ((uuid, file) in pending) {
            val stats = try {
                parseStats(file.readText())
            } catch (e: Exception) {
                log.warn("stats import: $file is unreadable and skipped (${e.message})")
                continue
            }
            if (stats.isEmpty()) continue
            publish(StatsSnapshot(PlayerRef(uuid, names[uuid] ?: uuid.toString()), stats))
            imported++
        }
        log.info("stats import: $imported of ${files.size} players imported (${files.size - pending.size} already in the Journal)")
        return imported
    }

    /** The players that have totals in the Journal. */
    private suspend fun knownPlayers(journal: Journal): Set<UUID> = journal.read(READ_TIMEOUT_MS) { connection ->
        connection.createStatement().use { statement ->
            statement.queryTimeout = (READ_TIMEOUT_MS / 1000).toInt()
            statement.executeQuery("SELECT DISTINCT CAST(player_uuid AS VARCHAR) FROM stat_totals").use { rows ->
                val uuids = HashSet<UUID>()
                while (rows.next()) uuids += UUID.fromString(rows.getString(1))
                uuids
            }
        }
    }

    /** `<uuid>.json` files of [dir], by UUID; files with another name are ignored. */
    private fun statsFiles(dir: Path): Map<UUID, Path> {
        if (!dir.isDirectory()) return emptyMap()
        return dir.listDirectoryEntries().filter { it.isRegularFile() && it.extension == "json" }
            .mapNotNull { file -> runCatching { UUID.fromString(file.nameWithoutExtension) }.getOrNull()?.let { it to file } }
            .toMap()
    }

    private fun readNames(userCache: Path): Map<UUID, String> {
        if (!Files.isRegularFile(userCache)) return emptyMap()
        return try {
            parseNames(userCache.readText())
        } catch (e: Exception) {
            log.warn("stats import: $userCache is unreadable, players are named by UUID (${e.message})")
            emptyMap()
        }
    }

    /** Stat name → total, from the text of a stats file. Throws when it is not a stats file. */
    fun parseStats(text: String): Map<String, Long> {
        val stats = json.parseToJsonElement(text).jsonObject["stats"]?.jsonObject ?: throw IllegalArgumentException("no \"stats\" object")
        val totals = LinkedHashMap<String, Long>()
        for ((type, values) in stats) {
            val byValue = values as? JsonObject ?: continue
            for ((value, total) in byValue) {
                val number = total.jsonPrimitive.longOrNull ?: continue
                totals["${key(type)}:${key(value)}"] = number
            }
        }
        return totals
    }

    /** UUID → name, from the text of `usercache.json` (an array of `{"name", "uuid", "expiresOn"}`). */
    fun parseNames(text: String): Map<UUID, String> {
        val names = HashMap<UUID, String>()
        for (entry in json.parseToJsonElement(text).jsonArray) {
            val profile = entry as? JsonObject ?: continue
            val name = profile["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val uuid = profile["uuid"]?.jsonPrimitive?.contentOrNull?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: continue
            names[uuid] = name
        }
        return names
    }

    /** A registry id as a stat name part: `minecraft:stone` → `minecraft.stone` (a bare path gets `minecraft`). */
    private fun key(id: String): String = (if (':' in id) id else "minecraft:$id").replace(':', '.')
}
