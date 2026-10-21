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

    companion object {
        private const val MILLION = 1_000_000.0

        /** The prices in force in [config]. */
        fun of(config: NarratorConfig) = Prices(config.prices)

        /**
         * Dollars as the dashboard and `/narrator usage` show them: with cents from one dollar up (`$12.40`, `$1.92`), two
         * significant digits below (`$0.042`, `$0.00011`: a reflex costs a fraction of a cent); `$0`; `$ ?` for an
         * unknown cost.
         */
        fun dollars(amount: Double?): String = when {
            amount == null -> "$ ?"
            amount == 0.0 -> "$0"
            amount >= 1 -> "$" + String.format(Locale.ROOT, "%.2f", amount)
            else -> "$" + BigDecimal(amount).round(MathContext(2)).toPlainString()
        }
    }
}
