package io.github.clemsix6.narrator2.forge.arsenal

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.forge.Arsenal
import io.github.clemsix6.narrator2.forge.ArsenalEntry
import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.ForgeRejected
import io.github.clemsix6.narrator2.forge.ModuleStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * The arsenal (M5 §6.1): the modules' sources, versioned and immutable, and their manifests.
 *
 * ```
 * <dir> = world/narrator2/forge/
 *   statues/v1.java   the source, never rewritten nor deleted
 *   statues/v1.json   its manifest (Manifest.encode); the version exists once it is there
 * narrator2/forge/arsenal/orbiting_chickens.java (in the mod's jar): a built-in module, version 0, read-only
 * ```
 *
 * **On disk.** A save writes the source, then the manifest, each to a temporary file of the module's folder (flushed
 * to the disk) moved in place in one step: a crash leaves either nothing or a whole file, never half of one, and a
 * version exists only once its manifest is there (a source without one is a save that did not finish: ignored,
 * its number never used again). A version is never written twice: its number is the next one, and a move never
 * replaces a file, but the manifest's status when an operator reviews it ([review]). [load] removes the temporary
 * files a crash left.
 *
 * **Paths.** Every path is built from the folder's real path, a name that passed [Manifest.nameProblem] and a
 * version number: nothing the model writes leaves the folder (no separator, dot, colon, device name of Windows). A
 * module's folder or file that is a link (a symbolic link, a junction of Windows) or anything but a plain folder or
 * file is never followed: skipped when the arsenal is read, refused when it is written.
 *
 * **Threads.** The reads of the index ([list], [entry], [file]) and the notes kept in memory ([markBroken],
 * [noteFault], [addBuiltIn]) run on any thread; every file is read and written on `Dispatchers.IO`, writes one at a
 * time: never on the server thread.
 *
 * **Status.** A version's status is its manifest's (`ready`, `pending`, `rejected`), but `broken`: computed, never
 * saved — its source no longer matches its hash ([load], [checkedSource]), or it no longer compiles against the SDK
 * of now ([markBroken], by the Forge).
 *
 * The built-in modules ([builtInNames], read by [readBuiltIn]) are listed once the Forge has compiled them and
 * read their manifests ([addBuiltIn]); their sources are in [builtInSources] from [load] on.
 */
class ArsenalStore(
    dir: Path,
    private val clock: Clock,
    private val builtInNames: List<String> = BUILT_IN,
    private val readBuiltIn: (String) -> ByteArray? = ::bundled,
    private val beforeMove: (Path) -> Unit = {},
) : Arsenal {
    private val dir: Path = dir.toAbsolutePath().normalize()
    private val log = Log.forge
    private val writes = Mutex()

    /** [dir]'s real path, once [load] has made sure it exists. */
    @Volatile
    private var root: Path? = null

    /** The versions as read or saved; their view adds [broken] and [faults]. */
    private val saved = ConcurrentHashMap<Key, ArsenalEntry>()
    private val broken = ConcurrentHashMap<Key, String>()
    private val faults = ConcurrentHashMap<Key, String>()

    /** A forged module's highest version number on disk, whatever its state: a number is never used twice. */
    private val taken = ConcurrentHashMap<String, Int>()

    /** The built-in modules' sources (name → source), from [load] on. */
    @Volatile
    var builtInSources: Map<String, String> = emptyMap()
        private set

    private data class Key(val name: String, val version: Int)

    /**
     * Reads the arsenal (M5 §6.1), once: the built-in sources, then every forged version's manifest, and checks each
     * source against its hash (`broken` when it does not match, is missing or is not a plain file). Removes the
     * temporary files of an unfinished save. Throws [IOException] when the folder cannot be made or listed.
     */
    suspend fun load() {
        writes.withLock { withContext(Dispatchers.IO) { loadIfNeeded() } }
    }

    /** Built-in module [manifest] (compiled by the Forge from [builtInSources]) becomes version 0, ready. */
    fun addBuiltIn(manifest: Manifest): ArsenalEntry {
        val source = requireNotNull(builtInSources[manifest.name]) { "${manifest.name} is not a built-in module" }
        val entry = manifest.entry(0, ModuleStatus.READY, builtIn = true, savedAt = Instant.EPOCH, by = BUILT_IN_BY, note = null, hash = Manifest.hash(source))
        saved[Key(entry.name, 0)] = entry
        broken.remove(Key(entry.name, 0))
        return entry
    }

    /**
     * Saves [source] as the next version of module [manifest] (its manifest as [Manifest.read] found it), with
     * [status] `ready` or `pending`, [note] (one English line) and [by] (`forger T-31`). Throws [ForgeRejected]: a
     * bad name, a source too long or holding a broken character (`Invalid arguments`), the arsenal full
     * (`DENIED (arsenal_full)`, 64 modules, 20 versions each), or the disk failing (`FAILED`).
     */
    suspend fun save(manifest: Manifest, source: String, note: String, by: String, status: ModuleStatus): ArsenalEntry {
        require(status == ModuleStatus.READY || status == ModuleStatus.PENDING) { "a version is saved ready or pending, not ${status.id}" }
        val name = manifest.name
        Manifest.nameProblem(name)?.let { throw ForgeRejected(null, it) }
        if (source.length > ForgeLimits.SOURCE_CHARS) throw ForgeRejected(null, "the source is ${source.length} characters long, at most ${ForgeLimits.SOURCE_CHARS}")
        val bytes = strictUtf8(source) ?: throw ForgeRejected(null, "the source holds a broken character (half of a surrogate pair): write it again")
        return writes.withLock {
            withContext(Dispatchers.IO) {
                loadForWrite()
                val version = nextVersion(name)
                val entry = manifest.entry(
                    version, status, builtIn = false, savedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS), by = by,
                    note = note.trim().ifEmpty { null }, hash = Manifest.hash(bytes),
                )
                val folder = try {
                    folder(name, create = true)!!
                } catch (e: IOException) {
                    throw failed("cannot save $name v$version", e)
                }
                val sourceFile = folder.resolve(sourceName(version))
                val manifestFile = folder.resolve(manifestName(version))
                var sourceWritten = false
                try {
                    writeNew(sourceFile, bytes)
                    sourceWritten = true
                    writeNew(manifestFile, Manifest.encode(entry).toByteArray(Charsets.UTF_8))
                } catch (e: IOException) {
                    // The version does not exist without its manifest: the source this save wrote goes too (or stays,
                    // an ignored orphan). A file it found in its way is never touched, and its number never used.
                    if (sourceWritten) runCatching { Files.deleteIfExists(sourceFile) }
                    if (Files.exists(sourceFile, LinkOption.NOFOLLOW_LINKS) || Files.exists(manifestFile, LinkOption.NOFOLLOW_LINKS)) {
                        taken.merge(name, version, ::maxOf)
                    }
                    throw failed("cannot save $name v$version", e)
                }
                saved[Key(name, version)] = entry
                taken.merge(name, version, ::maxOf)
                log.info("arsenal: saved $name v$version (${status.id}) by $by: ${entry.classes} classes, ${entry.bytes} bytes, ${entry.hash}")
                entry
            }
        }
    }

    /**
     * `/narrator forge approve|reject` (M5 §6.3): [name] at [version] (its latest pending one when null) becomes
     * `ready` or `rejected`; its manifest is written again with that status, nothing else changes. Throws
     * [ForgeRejected] for a version that is unknown or not pending (`Invalid arguments`), or the disk failing.
     */
    suspend fun review(name: String, version: Int?, approve: Boolean, operator: String): ArsenalEntry = writes.withLock {
        withContext(Dispatchers.IO) {
            loadForWrite()
            Manifest.nameProblem(name)?.let { throw ForgeRejected(null, it) }
            val key = if (version == null) {
                saved.keys.filter { it.name == name && view(it)?.status == ModuleStatus.PENDING }.maxByOrNull { it.version }
                    ?: throw ForgeRejected(null, "$name has no version waiting for approval")
            } else {
                Key(name, version)
            }
            val current = view(key) ?: throw ForgeRejected(null, "no module $name v${key.version} in the arsenal")
            if (current.status != ModuleStatus.PENDING) {
                throw ForgeRejected(null, "$name v${key.version} is ${current.status.id}: only a pending version is approved or rejected")
            }
            val reviewed = saved.getValue(key).copy(status = if (approve) ModuleStatus.READY else ModuleStatus.REJECTED)
            try {
                val folder = folder(name, create = false) ?: throw NoSuchFileException(dir.resolve(name).toString())
                replace(folder.resolve(manifestName(key.version)), Manifest.encode(reviewed).toByteArray(Charsets.UTF_8))
            } catch (e: IOException) {
                throw failed("cannot record the review of $name v${key.version}", e)
            }
            saved[key] = reviewed
            log.info("arsenal: $name v${key.version} ${if (approve) "approved" else "rejected"} by $operator")
            reviewed
        }
    }

    /**
     * Version [version] of [name] is `broken` until the server restarts (M5 §6.1, §6.7): [problem] is its first
     * diagnostic (it no longer compiles against the SDK of now) or why its source cannot be trusted. Kept in memory,
     * never saved. Returns the entry, or null when there is none. Any thread.
     */
    fun markBroken(name: String, version: Int, problem: String): ArsenalEntry? {
        val key = Key(name, version)
        if (!saved.containsKey(key)) return null
        if (broken.put(key, problem) != problem) log.warn("arsenal: $name v$version is broken: $problem")
        return view(key)
    }

    /** The last [fault] of a deployment of [name] at [version] (M5 §3.7), shown to the forger; in memory. Any thread. */
    fun noteFault(name: String, version: Int, fault: String): ArsenalEntry? {
        val key = Key(name, version)
        if (!saved.containsKey(key)) return null
        faults[key] = fault
        return view(key)
    }

    /**
     * The source of [name] at [version] to build it: a forged one is read again and checked against its hash; one
     * that does not match (changed on disk), is missing or is not a plain file becomes `broken` ([markBroken]) and
     * throws [ForgeRejected] (`FAILED`). Also throws for an unknown version. Never on the server thread.
     */
    suspend fun checkedSource(name: String, version: Int): String {
        val entry = saved[Key(name, version)] ?: throw ForgeRejected(null, "no module $name v$version in the arsenal")
        if (entry.builtIn) return builtInSources.getValue(name)
        val bytes = withContext(Dispatchers.IO) {
            try {
                readSource(folder(name, create = false), version, entry.hash)
            } catch (e: IOException) {
                markBroken(name, version, e.message.orEmpty())
                throw ForgeRejected(null, "$name v$version is broken: ${e.message}", failed = true)
            }
        }
        return String(bytes, Charsets.UTF_8)
    }

    override fun list(): List<ArsenalEntry> = saved.keys.mapNotNull { view(it) }.sortedWith(NEWEST_FIRST)

    override fun entry(name: String, version: Int?): ArsenalEntry? = if (version == null) {
        saved.keys.filter { it.name == name }.mapNotNull { view(it) }.filter { it.status == ModuleStatus.READY }.maxByOrNull { it.version }
    } else {
        view(Key(name, version))
    }

    override suspend fun source(name: String, version: Int): String? {
        val entry = saved[Key(name, version)]
        if (entry == null) return builtInSources[name].takeIf { version == 0 }
        if (entry.builtIn) return builtInSources[name]
        return withContext(Dispatchers.IO) {
            try {
                String(readSource(folder(name, create = false), version, expectedHash = null), Charsets.UTF_8)
            } catch (e: IOException) {
                log.warn("arsenal: cannot read the source of $name v$version: ${e.message}")
                null
            }
        }
    }

    override fun file(name: String, version: Int): Path? {
        val entry = saved[Key(name, version)] ?: return null
        if (entry.builtIn) return null
        return root?.resolve(name)?.resolve(sourceName(version))
    }

    private fun view(key: Key): ArsenalEntry? {
        val entry = saved[key] ?: return null
        broken[key]?.let { return entry.copy(status = ModuleStatus.BROKEN, problem = it) }
        return faults[key]?.let { entry.copy(problem = it) } ?: entry
    }

    // ── Reading ────────────────────────────────────────────────────────────────────────────────────────────────

    /** [loadIfNeeded] before a write: an arsenal that cannot be read is the disk failing. */
    private fun loadForWrite() = try {
        loadIfNeeded()
    } catch (e: IOException) {
        throw failed("cannot read the arsenal", e)
    }

    private fun loadIfNeeded() {
        if (root != null) return
        val started = System.nanoTime()
        saved.clear()
        broken.clear()
        taken.clear()
        builtInSources = readBuiltIns()
        Files.createDirectories(dir)
        val real = dir.toRealPath()
        val ignored = mutableListOf<String>()
        var leftovers = 0
        Files.newDirectoryStream(real).use { children ->
            for (folder in children) {
                val name = folder.fileName.toString()
                when {
                    Manifest.nameProblem(name) != null -> ignored += name
                    !isPlainFolder(folder) -> log.warn("arsenal: $folder is not a plain folder (a file, a link?): skipped, never followed")
                    else -> leftovers += readModule(name, folder, ignored)
                }
            }
        }
        root = real
        if (leftovers > 0) log.info("arsenal: removed $leftovers temporary files of saves that did not finish")
        if (ignored.isNotEmpty()) log.warn("arsenal: ignored in $real, not a module's file or folder: ${ignored.sorted().joinToString()}")
        val forged = saved.values.filter { !it.builtIn }
        log.info(
            "arsenal: ${builtInSources.size} built-in modules, ${forged.size} forged versions of ${forged.map { it.name }.toSet().size} modules " +
                "(${broken.size} broken) read from $real in ${(System.nanoTime() - started) / 1_000_000} ms",
        )
    }

    private fun readBuiltIns(): Map<String, String> = builtInNames.mapNotNull { name ->
        val bytes = if (Manifest.nameProblem(name) == null) readBuiltIn(name) else null
        if (bytes == null) log.error("arsenal: the built-in module $name is missing from the mod's jar ($RESOURCES$name.java)")
        bytes?.let { name to String(it, Charsets.UTF_8) }
    }.toMap()

    /** Reads module [name]'s versions in [folder]; returns how many temporary files it removed. */
    private fun readModule(name: String, folder: Path, ignored: MutableList<String>): Int {
        val manifests = mutableMapOf<Int, Path>()
        val sources = mutableSetOf<Int>()
        var leftovers = 0
        Files.newDirectoryStream(folder).use { files ->
            for (file in files) {
                val fileName = file.fileName.toString()
                val match = VERSION_FILE.matchEntire(fileName)
                when {
                    TEMPORARY.matches(fileName) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) -> if (Files.deleteIfExists(file)) leftovers++
                    match == null -> ignored += "$name/$fileName"
                    match.groupValues[2] == "json" -> manifests[match.groupValues[1].toInt()] = file
                    else -> sources += match.groupValues[1].toInt()
                }
            }
        }
        (manifests.keys + sources).maxOrNull()?.let { taken[name] = it }
        (sources - manifests.keys).forEach { log.warn("arsenal: $name v$it has a source but no manifest (a save that did not finish): ignored") }
        manifests.forEach { (version, file) -> readVersion(name, version, folder, file) }
        return leftovers
    }

    private fun readVersion(name: String, version: Int, folder: Path, manifestFile: Path) {
        val entry = try {
            Manifest.decode(String(readPlainFile(manifestFile, MANIFEST_BYTES), Charsets.UTF_8))
        } catch (e: Exception) {
            log.error("arsenal: cannot read $manifestFile, so $name v$version is skipped (its number stays taken): ${e.message}")
            return
        }
        if (entry.name != name || entry.version != version) {
            log.error("arsenal: $manifestFile describes another version than $name v$version: skipped (its number stays taken)")
            return
        }
        val key = Key(name, version)
        saved[key] = entry
        try {
            readSource(folder, version, entry.hash)
        } catch (e: IOException) {
            broken[key] = e.message.orEmpty()
            log.error("arsenal: $name v$version is broken: ${e.message}")
        }
    }

    /** The bytes of the source at [version] in a module's [folder] (null: missing), checked against [expectedHash] when given. */
    private fun readSource(folder: Path?, version: Int, expectedHash: String?): ByteArray {
        if (folder == null) throw IOException("its folder is missing")
        val bytes = try {
            readPlainFile(folder.resolve(sourceName(version)), SOURCE_BYTES)
        } catch (e: NoSuchFileException) {
            throw IOException("its source ${sourceName(version)} is missing")
        }
        if (expectedHash != null && Manifest.hash(bytes) != expectedHash) {
            throw IOException("its source ${sourceName(version)} no longer matches its hash: it was changed on disk")
        }
        return bytes
    }

    /** The bytes of [file], a plain file (never through a link) of [limit] bytes at most. */
    private fun readPlainFile(file: Path, limit: Int): ByteArray {
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.isOther) throw IOException("${file.fileName} is not a plain file (a link?)")
        if (attributes.size() > limit) throw IOException("${file.fileName} is ${attributes.size()} bytes long, at most $limit")
        Files.newByteChannel(file, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            val buffer = ByteBuffer.allocate(limit + 1)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
            if (buffer.position() > limit) throw IOException("${file.fileName} is more than $limit bytes long")
            return buffer.array().copyOf(buffer.position())
        }
    }

    // ── Writing ────────────────────────────────────────────────────────────────────────────────────────────────

    private fun nextVersion(name: String): Int {
        val highest = taken[name] ?: 0
        val names = builtInSources.keys + taken.keys
        if (name !in names && names.size >= ForgeLimits.ARSENAL_NAMES) {
            throw ForgeRejected(
                ARSENAL_FULL,
                "the arsenal holds ${names.size} modules, the most it may: make a new version of one of them rather than a new module",
            )
        }
        if (highest >= ForgeLimits.VERSIONS_PER_NAME) {
            throw ForgeRejected(ARSENAL_FULL, "$name has $highest versions, the most a module may have: save it under a new name")
        }
        return highest + 1
    }

    /**
     * Module [name]'s folder in the arsenal, made when [create] and missing; null when missing. Throws
     * [IOException] when it exists but is not a plain folder of the arsenal (a link, a junction, a file).
     */
    private fun folder(name: String, create: Boolean): Path? {
        val real = checkNotNull(root) { "the arsenal is not loaded" }
        check(Manifest.nameProblem(name) == null) { "not a module's name: $name" }
        val folder = real.resolve(name)
        check(folder.parent == real) { "$folder is not in $real" }
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) return null
            Files.createDirectory(folder)
        }
        if (!isPlainFolder(folder)) throw IOException("$folder is not a plain folder of the arsenal (a link?)")
        return folder
    }

    /** A folder, not a link to one (symbolic link, junction of Windows), whose real path is where it stands. */
    private fun isPlainFolder(folder: Path): Boolean {
        val attributes = Files.readAttributes(folder, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        return attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther && folder.toRealPath() == folder
    }

    /** Writes [target], a file that must not exist: [bytes] to a temporary file of its folder, flushed, then moved in place. */
    private fun writeNew(target: Path, bytes: ByteArray) = write(target, bytes) { temporary ->
        // A move in one step replaces a file on every system: the check keeps a version from being written twice.
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw FileAlreadyExistsException(target.toString())
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Writes [target], an existing manifest, again: the same way, the move replacing it (never what a link points to) in one step. */
    private fun replace(target: Path, bytes: ByteArray) = write(target, bytes) { temporary ->
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun write(target: Path, bytes: ByteArray, move: (Path) -> Unit) {
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            beforeMove(temporary)
            move(temporary)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun failed(what: String, cause: IOException): ForgeRejected {
        log.error("arsenal: $what: $cause")
        return ForgeRejected(null, "$what: the disk failed (${cause.javaClass.simpleName}: ${cause.message})", failed = true)
    }

    companion object {
        /** The built-in modules (M5 §10), each `narrator2/forge/arsenal/<name>.java` in the mod's jar. */
        val BUILT_IN = listOf("orbiting_chickens", "parade", "tag", "simon_says", "hot_cold", "floor_is_lava", "gravity_storm")

        /** Who saved a built-in module. */
        const val BUILT_IN_BY = "built-in"

        /** The rule of a save refused because the arsenal holds as much as it may: `DENIED (arsenal_full): …`. */
        const val ARSENAL_FULL = "arsenal_full"

        private const val RESOURCES = "/narrator2/forge/arsenal/"

        /** The largest source and manifest read: a file larger than any save could write is not read whole. */
        private const val SOURCE_BYTES = 4 * ForgeLimits.SOURCE_CHARS
        private const val MANIFEST_BYTES = 64 * 1024

        private val VERSION_FILE = Regex("v([1-9][0-9]{0,3})\\.(java|json)")
        private val TEMPORARY = Regex("\\.v[1-9][0-9]{0,3}\\.(java|json)\\..*\\.tmp")

        private val NEWEST_FIRST = compareByDescending<ArsenalEntry> { it.savedAt }.thenBy { it.name }.thenByDescending { it.version }

        fun sourceName(version: Int) = "v$version.java"

        fun manifestName(version: Int) = "v$version.json"

        /** A built-in module's source in the mod's jar; null when absent. */
        private fun bundled(name: String): ByteArray? = ArsenalStore::class.java.getResourceAsStream("$RESOURCES$name.java")?.use { it.readAllBytes() }

        /** [text] in UTF-8, or null when it holds half of a surrogate pair (which UTF-8 cannot write back). */
        private fun strictUtf8(text: String): ByteArray? = try {
            val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val buffer = encoder.encode(CharBuffer.wrap(text))
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
