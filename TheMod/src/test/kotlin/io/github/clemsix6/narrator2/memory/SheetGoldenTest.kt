package io.github.clemsix6.narrator2.memory

import org.junit.jupiter.api.Test
import java.io.File
import java.time.ZoneOffset
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The three renderings of the reference sheet (M3 §11.4, §15.1), byte for byte, against
 * `src/test/resources/narrator2/memory/sheets/`. A difference writes the rendering to `build/golden/sheets/`
 * for review: replace the expected file only when the new rendering is the one wanted.
 */
class SheetGoldenTest {
    private val renderer = SheetRenderer(ZoneOffset.UTC)
    private val now = SheetSamples.NOW.toEpochMilli()

    @Test
    fun `full sheet of the reference`() = golden("bartok.full.txt", SheetDetail.FULL)

    @Test
    fun `condensed sheet of the reference`() = golden("bartok.condensed.txt", SheetDetail.CONDENSED)

    @Test
    fun `one line of the reference`() = golden("bartok.line.txt", SheetDetail.LINE)

    private fun golden(name: String, detail: SheetDetail) {
        val actual = renderer.render(SheetSamples.reference, detail, now)
        assertTrue(actual.length <= detail.maxChars, "$name: ${actual.length} characters, over ${detail.maxChars}")
        val expected = javaClass.getResource("/narrator2/memory/sheets/$name")?.readText(Charsets.UTF_8)?.replace("\r\n", "\n")?.removeSuffix("\n")
        if (expected != actual) {
            val written = File("build/golden/sheets/$name").apply {
                parentFile.mkdirs()
                writeText(actual + "\n", Charsets.UTF_8)
            }
            fail("$name is not the expected sheet; the rendering was written to ${written.absolutePath}:\n$actual")
        }
    }
}
