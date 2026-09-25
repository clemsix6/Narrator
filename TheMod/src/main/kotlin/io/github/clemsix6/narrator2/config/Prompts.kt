package io.github.clemsix6.narrator2.config

import io.github.clemsix6.narrator2.core.Log
import java.nio.file.Files
import java.nio.file.Path

/**
 * Prompt files. Defaults ship in the jar under `narrator2/prompts/`; on first run they are copied to
 * `config/narrator2/prompts/`, where they can be edited and reloaded with `/narrator reload`.
 */
class Prompts private constructor(private val texts: Map<String, String>) {

    fun get(name: String): String = texts[name] ?: error("prompt inconnu : $name")

    companion object {
        /** Every prompt the mod ships. Adding a prompt means adding its file name here. */
        val NAMES = listOf("director")

        fun load(dir: Path): Prompts {
            Files.createDirectories(dir)
            val texts = NAMES.associateWith { name ->
                val file = dir.resolve("$name.md")
                if (!Files.exists(file)) {
                    Files.writeString(file, bundled(name))
                    Log.config.info("prompt par défaut copié : ${file.fileName}")
                }
                Files.readString(file)
            }
            return Prompts(texts)
        }

        private fun bundled(name: String): String {
            val stream = Prompts::class.java.getResourceAsStream("/narrator2/prompts/$name.md")
                ?: error("prompt absent du jar : $name")
            return stream.use { String(it.readAllBytes(), Charsets.UTF_8) }
        }
    }
}
