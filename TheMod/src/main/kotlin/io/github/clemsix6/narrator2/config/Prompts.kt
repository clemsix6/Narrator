package io.github.clemsix6.narrator2.config

import io.github.clemsix6.narrator2.core.Log
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/**
 * The Narrator's prompts. They ship in the jar under `narrator2/prompts/`. A file with the same name in
 * the override folder (`config/narrator2/prompts/<name>.md`) replaces the bundled prompt; nothing is
 * copied there automatically, so the bundled prompts stay current with the mod.
 */
class Prompts private constructor(private val texts: Map<String, String>) {

    fun get(name: String): String = texts[name] ?: error("unknown prompt: $name")

    companion object {
        /** Every prompt the mod ships. Adding a prompt means adding its file name here. */
        val NAMES = listOf("director", "analyst", "chapter_summary", "ping")

        const val README = "README.md"

        private val log = Log.config

        /**
         * Loads every prompt, from [overrideDir] when it has an override, else from the jar, and logs the
         * source of each. Creates [overrideDir] with a README the first time.
         */
        fun load(overrideDir: Path): Prompts {
            if (!Files.exists(overrideDir)) createOverrideDir(overrideDir)
            val texts = NAMES.associateWith { name ->
                val file = overrideDir.resolve("$name.md")
                if (Files.isRegularFile(file)) {
                    log.info("prompt $name: override ${file.toAbsolutePath()}")
                    Files.readString(file)
                } else {
                    log.info("prompt $name: bundled")
                    bundled(name)
                }
            }
            overrideDir.listDirectoryEntries("*.md")
                .filter { it.name != README && it.nameWithoutExtension !in NAMES }
                .forEach { log.warn("${it.toAbsolutePath()} is ignored: no prompt is named ${it.nameWithoutExtension}") }
            return Prompts(texts)
        }

        /**
         * The prompts of the jar, overrides ignored: what the Narrator runs with when its configuration
         * cannot be loaded.
         */
        fun bundled(): Prompts = Prompts(NAMES.associateWith(::bundled))

        private fun createOverrideDir(dir: Path) {
            Files.createDirectories(dir)
            Files.writeString(
                dir.resolve(README),
                """
                # Prompt overrides

                The Narrator's prompts ship inside the mod. To replace one, put a file with its name here,
                then run `/narrator reload`. Delete the file to go back to the prompt of the mod.

                Prompts: ${NAMES.joinToString { "`$it.md`" }}.

                The server log says, at each start and reload, which source every prompt comes from.

                """.trimIndent(),
            )
            log.info("created ${dir.toAbsolutePath()} for prompt overrides")
        }

        private fun bundled(name: String): String {
            val stream = Prompts::class.java.getResourceAsStream("/narrator2/prompts/$name.md")
                ?: error("prompt missing from the jar: $name")
            return stream.use { String(it.readAllBytes(), Charsets.UTF_8) }
        }
    }
}
