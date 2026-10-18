package io.github.clemsix6.narrator2.memory.stats

/**
 * Jev's personality signals, gathered per player (M3 §6.3) into the `signals` stat: over the `chat_signals`
 * rows whose source starts with `jev:`, for each of the five signals its mean, its exponential moving
 * average (half-life of [HALF_LIFE_MESSAGES] messages, the latest weighing most) and its count `n`. The
 * heuristic's rows are left out: its signals are null, and nothing is made up from them.
 *
 * `value` is the number of messages Jev measured; `detail` is null under [MIN_MESSAGES] ("too few
 * messages"), else `{politeness: {mean, ewma, n}, boastfulness, helpfulness, aggressiveness, humor}`. These
 * are hints in figures for the sheet and the Dreamer, never traits: the Dreamer decides, with evidence.
 * Computed with the other stats ([SheetStats.compute]).
 */
object Signals {
    const val HALF_LIFE_MESSAGES = 100.0
    const val MIN_MESSAGES = 30

    /** The five signals, as the `chat_signals` columns. */
    val NAMES: List<String> = listOf("politeness", "boastfulness", "helpfulness", "aggressiveness", "humor")

    private fun signal(name: String) =
        "'$name': {'mean': round(avg($name), 3), " +
            "'ewma': round(sum($name * pow(2, -k / $HALF_LIFE_MESSAGES)) / sum(pow(2, -k / $HALF_LIFE_MESSAGES)) FILTER (WHERE $name IS NOT NULL), 3), " +
            "'n': count($name)}"

    /** The `signals` stat of [SheetStats]' query. */
    internal val STAT = SheetStats.Stat(
        "signals",
        ctes = listOf(
            """j AS (
            SELECT row_number() OVER (ORDER BY ts DESC, event_id DESC) - 1 AS k, ${NAMES.joinToString()}
            FROM chat_signals WHERE player_uuid = {player} AND ts <= {now} AND starts_with(source, 'jev:'))""",
        ),
        value = "(SELECT count(*) FROM j)",
        detail = "(SELECT CASE WHEN count(*) >= $MIN_MESSAGES THEN to_json({${NAMES.joinToString { signal(it) }}}) END FROM j)",
    )
}
