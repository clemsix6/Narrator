package io.github.clemsix6.narrator2.trace

import io.github.clemsix6.narrator2.config.ModelPrice
import io.github.clemsix6.narrator2.config.NarratorConfig
import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale

/**
 * What model calls cost in dollars (M6 §8.7), from the `prices` of the configuration: dollars per million tokens, by
 * the model id that answered (`llm_calls.model`, a fallback model included). A model without a price has no cost
 * (`$ ?`): nothing is guessed from a name. Behind a subscription, an order of magnitude only.
 */
class Prices(private val table: Map<String, ModelPrice>) {

    /** The price of [model]; null when the configuration has none. */
    fun of(model: String): ModelPrice? = table[model]

    /** What these tokens of [model] cost, in dollars; null when [model] has no price. Cache writes at the 1 h rate. */
    fun cost(model: String, input: Long, output: Long, cacheRead: Long, cacheWrite: Long): Double? {
        val price = of(model) ?: return null
        return (input * price.input + output * price.output + cacheRead * price.cacheRead + cacheWrite * price.cacheWrite) / MILLION
    }

    /**
     * What [tokens] cost, each at the price of its model: the one computation of a cost that the dashboard and
     * `/narrator usage` share, shown by [Cost.text].
     */
    fun costOf(tokens: Iterable<ModelTokens>): Cost = tokens.fold(Cost.NONE) { sum, used ->
        val dollars = cost(used.model, used.input, used.output, used.cacheRead, used.cacheWrite)
        sum + if (dollars == null) Cost(0.0, 0, used.calls) else Cost(dollars, used.calls, 0)
    }

    companion object {
        private const val MILLION = 1_000_000.0

        /** The prices in force in [config]. */
        fun of(config: NarratorConfig) = Prices(config.prices)

        /**
         * Dollars as the dashboard and `/narrator usage` show them: with cents from one dollar up (`$12.40`, `$1.92`), two
         * significant digits below (`$0.042`, `$0.00011`: a reflex costs a fraction of a cent); `$0`; `$ ?` for an
         * unknown cost. Rounded to two significant digits first, so that a cost that rounds up to a dollar has its cents
         * (`0.996` is `$1.00`).
         */
        fun dollars(amount: Double?): String {
            if (amount == null) return "$ ?"
            if (amount == 0.0) return "$0"
            val rounded = BigDecimal(amount).round(MathContext(2))
            return "$" + if (rounded >= BigDecimal.ONE) String.format(Locale.ROOT, "%.2f", amount) else rounded.toPlainString()
        }
    }
}

/**
 * Calls to one [model] (the one that answered, `llm_calls.model`) and the tokens they used; a call that failed before any
 * reply used none.
 */
data class ModelTokens(
    val model: String,
    val calls: Long,
    val failed: Long,
    val input: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val output: Long,
) {
    /** The tokens the model read: input, cache read and cache write. */
    val read: Long get() = input + cacheRead + cacheWrite

    operator fun plus(other: ModelTokens) = ModelTokens(
        model, calls + other.calls, failed + other.failed, input + other.input, cacheRead + other.cacheRead,
        cacheWrite + other.cacheWrite, output + other.output,
    )

    companion object {
        /** The sum of [tokens], whatever their models, named [model]. */
        fun sum(model: String, tokens: Iterable<ModelTokens>) = tokens.fold(ModelTokens(model, 0, 0, 0, 0, 0, 0), ModelTokens::plus)
    }
}

/**
 * What calls cost (M6 §8.7): the [dollars] of the [priced] calls, at the configuration's prices, and the calls of a model
 * without a price ([unpriced]), whose cost is unknown (`$ ?`): nothing is guessed from a model's name.
 */
data class Cost(val dollars: Double, val priced: Long, val unpriced: Long) {
    /**
     * The one way a cost is written, on the dashboard and in `/narrator usage`: `$1.92`; `$ ?` when every call is of a
     * model without a price; `$1.92 + 3 calls at $ ?` when some are.
     */
    val text: String
        get() = when {
            unpriced == 0L -> Prices.dollars(dollars)
            priced == 0L -> Prices.dollars(null)
            else -> "${Prices.dollars(dollars)} + $unpriced ${if (unpriced == 1L) "call" else "calls"} at ${Prices.dollars(null)}"
        }

    operator fun plus(other: Cost) = Cost(dollars + other.dollars, priced + other.priced, unpriced + other.unpriced)

    companion object {
        /** No call. */
        val NONE = Cost(0.0, 0, 0)
    }
}
