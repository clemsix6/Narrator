package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.core.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * The players the Narrator never acts against (M1_M2 §10.3, §10.9): those of `/narrator protect`, kept
 * in `world/narrator2/protected.json`, and those named in `safety.protectedPlayers`, matched by name
 * (ignoring case) against the players seen online.
 *
 * The file is read once, when the server starts, and written off the server thread on each change, with
 * the latest list, through a temporary file (a crash never leaves half a file). An unreadable file is
 * kept aside as `protected.json.bad`, with an error in the log.
 *
 * @param configured the names of `safety.protectedPlayers`, read at each check (`/narrator reload`).
 * @param io where the file is written.
 */
class ProtectedPlayers(private val file: Path, private val configured: () -> List<String>, private val io: Executor) {
    private val log = Log.guard

    /** The players of `/narrator protect`, with their name. */
    private val protected = ConcurrentHashMap<UUID, String>()

    /** The last name seen of each player who joined, for the names of the configuration. */
    private val names = ConcurrentHashMap<UUID, String>()

    private val writeLock = Any()

    init {
        load()
    }

    /** Whether [uuid] is protected. Any thread. */
    fun isProtected(uuid: UUID): Boolean = protected.containsKey(uuid) || names[uuid]?.let(::isConfigured) == true

    /** Whether the player [uuid], now called [name], is protected. Any thread. */
    fun isProtected(uuid: UUID, name: String): Boolean = protected.containsKey(uuid) || isConfigured(name)

    /** Remembers the name of a player who joined. */
    fun seen(uuid: UUID, name: String) {
        names[uuid] = name
    }

    /** Protects [uuid] (as [name]) or lifts the protection, and saves the list; false when nothing changed. */
    fun set(uuid: UUID, name: String, on: Boolean): Boolean {
        val changed = if (on) protected.put(uuid, name) != name else protected.remove(uuid) != null
        if (changed) io.execute(::save)
        return changed
    }

    private fun isConfigured(name: String): Boolean = configured().any { it.equals(name, ignoreCase = true) }

    private fun load() {
        if (!Files.exists(file)) return
        try {
            val saved = json.decodeFromString(Saved.serializer(), Files.readString(file))
            saved.players.forEach { protected[UUID.fromString(it.uuid)] = it.name }
            log.info("${protected.size} players protected by /narrator protect")
        } catch (e: Exception) {
            val bad = file.resolveSibling("${file.fileName}.bad")
            log.error("cannot read $file, kept aside as $bad: no player is protected by /narrator protect until it is fixed", e)
            runCatching { Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING) }
        }
    }

    /** Writes the list as it is now. Runs on [io]; writes are one at a time, the last one wins. */
    private fun save() = synchronized(writeLock) {
        try {
            val saved = Saved(protected.entries.map { Entry(it.key.toString(), it.value) }.sortedBy { it.name.lowercase() })
            Files.createDirectories(file.parent)
            val temp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
            try {
                Files.writeString(temp, json.encodeToString(Saved.serializer(), saved) + "\n")
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temp)
            }
        } catch (e: Exception) {
            log.error("cannot write $file: the protection change holds until the server stops, not after", e)
        }
    }

    /** The file: `{"players": [{"uuid": "…", "name": "…"}]}`. */
    @Serializable
    private data class Saved(val players: List<Entry> = emptyList())

    @Serializable
    private data class Entry(val uuid: String, val name: String)

    private companion object {
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }
}
