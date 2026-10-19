package io.github.clemsix6.narrator2.core

import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec

/**
 * A text of any length in the game's files (player files, the world's attachments). NBT writes a string with
 * `DataOutput.writeUTF`, which takes 65,535 bytes at most (modified UTF-8: up to 3 bytes a character); past that,
 * the game logs "Failed to write NBT String" and writes `""` instead (`NbtIo$StringFallbackDataOutput`): the text is
 * lost at the next read, with nothing else said. [CODEC] writes a text of up to [CHUNK] characters as one string,
 * the way it always was, and a longer one as a list of strings of [CHUNK] characters at most: each fits.
 */
object LongString {
    /** The longest string written as such: 20,000 characters are 60,000 bytes at most, under the 65,535 of `writeUTF`. */
    const val CHUNK = 20_000

    /** One string up to [CHUNK] characters, a list of chunks past it; reads both. */
    val CODEC: Codec<String> = Codec.either(Codec.STRING, Codec.STRING.listOf()).xmap(
        { either -> either.map({ it }, { chunks -> chunks.joinToString("") }) },
        { text -> if (text.length <= CHUNK) Either.left(text) else Either.right(text.chunked(CHUNK)) },
    )
}
