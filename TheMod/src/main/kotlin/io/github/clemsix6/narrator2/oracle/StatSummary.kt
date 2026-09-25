package io.github.clemsix6.narrator2.oracle

/**
 * Vanilla stats as the model reads them. A stat's full name is the game's (`Stat.getName()`, also the
 * key of `stat_totals`): `minecraft.mined:minecraft.stone`, `minecraft.custom:minecraft.deaths`. The
 * model writes and reads the short form, `mined:stone`, `custom:deaths`, as in the `player_stats` view.
 * Pure: the same summary serves the live counter of a player online and the Journal's totals.
 */
object StatSummary {
    /** Stat types, by short name: the part before `:`. */
    val TYPES = listOf("custom", "mined", "crafted", "used", "broken", "picked_up", "dropped", "killed", "killed_by")

    /**
     * The full name of a stat the model wrote: `deaths` (a custom stat), `custom:deaths`,
     * `mined:diamond_ore`, `minecraft.mined:minecraft.stone`, `create.custom:create.x`. Null when the
     * type is not a stat type.
     */
    fun fullName(raw: String): String? {
        val text = raw.trim().lowercase()
        val (type, value) = if (':' in text) text.substringBefore(':') to text.substringAfter(':') else "custom" to text
        if (type.isEmpty() || value.isEmpty()) return null
        val fullType = namespaced(type)
        if (fullType.startsWith("minecraft.") && fullType.removePrefix("minecraft.") !in TYPES) return null
        return "$fullType:${namespaced(value)}"
    }

    /** `minecraft.mined:minecraft.stone` → `mined:stone`; other namespaces stay (`create.custom:create.x`). */
    fun short(full: String): String = full.split(':').joinToString(":") { it.removePrefix("minecraft.") }

    /**
     * A summary of [values] (full name → value) in a few lines: time played and deaths, kills, mining,
     * blocks placed (estimated from the block items used; [isBlockItem] tells them by full item id,
     * `minecraft:stone`), distances, damage and the rarer feats.
     */
    fun summary(values: Map<String, Long>, isBlockItem: (String) -> Boolean): List<String> {
        fun custom(name: String) = values["minecraft.custom:minecraft.$name"] ?: 0L
        fun sumOf(prefix: String, filter: (String) -> Boolean = { true }) =
            values.entries.filter { it.key.startsWith(prefix) && filter(it.key.removePrefix(prefix)) }.sumOf { it.value }

        val lines = ArrayList<String>()
        val deaths = custom("deaths")
        val lastDeath = if (deaths == 0L) "never died" else "deaths $deaths (last ${OracleText.duration(custom("time_since_death") * MS_PER_TICK)} of play ago)"
        lines += "play time ${OracleText.duration(custom("play_time") * MS_PER_TICK)} · $lastDeath"

        val killedByPlayers = values["minecraft.killed_by:minecraft.player"] ?: 0L
        lines += "kills: ${custom("mob_kills")} mobs, ${custom("player_kills")} players · killed by players $killedByPlayers times"

        val mined = sumOf("minecraft.mined:")
        val diamonds = (values["minecraft.mined:minecraft.diamond_ore"] ?: 0L) + (values["minecraft.mined:minecraft.deepslate_diamond_ore"] ?: 0L)
        val debris = values["minecraft.mined:minecraft.ancient_debris"] ?: 0L
        lines += "mined $mined blocks (diamond ore $diamonds, ancient debris $debris)"

        val placed = sumOf("minecraft.used:") { isBlockItem(it.replaceFirst('.', ':')) }
        lines += "placed about $placed blocks"

        val travels = values.entries
            .filter { it.key.startsWith("minecraft.custom:minecraft.") && it.key.endsWith("_one_cm") && it.value > 0 && !it.key.endsWith("fall_one_cm") }
            .sortedByDescending { it.value }
        if (travels.isNotEmpty()) {
            val total = travels.sumOf { it.value }
            val modes = travels.take(4).joinToString { "${travel(it.key)} ${OracleText.metres(it.value)}" }
            lines += "travelled ${OracleText.metres(total)} ($modes)"
        }

        // Damage stats are tenths of a health point; a heart is 2 points.
        lines += "damage dealt ${custom("damage_dealt") / 20} hearts, taken ${custom("damage_taken") / 20} hearts"

        val feats = listOf(
            "traded_with_villager" to "trades", "enchant_item" to "enchantments", "animals_bred" to "animals bred",
            "fish_caught" to "fish caught", "sleep_in_bed" to "nights in a bed", "raid_win" to "raids won", "jump" to "jumps",
        ).mapNotNull { (name, label) -> custom(name).takeIf { it > 0 }?.let { "$label $it" } }
        if (feats.isNotEmpty()) lines += feats.joinToString(" · ")
        return lines
    }

    /** `minecraft.custom:minecraft.aviate_one_cm` → `elytra`; `walk_one_cm` → `walk`. */
    private fun travel(full: String): String = when (val name = full.substringAfter(":minecraft.").removeSuffix("_one_cm")) {
        "aviate" -> "elytra"
        else -> name.replace('_', ' ')
    }

    private fun namespaced(part: String): String = if ('.' in part || ':' in part) part.replace(':', '.') else "minecraft.$part"

    private const val MS_PER_TICK = 50L
}
