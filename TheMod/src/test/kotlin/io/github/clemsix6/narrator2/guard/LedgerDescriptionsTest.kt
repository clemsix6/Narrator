package io.github.clemsix6.narrator2.guard

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** How ledger entries read in decisions and in `activeOn` (the model sees them). */
class LedgerDescriptionsTest {
    private fun modifier(attribute: String, amount: Double, operation: String) = LedgerModifier(attribute, amount, operation, 0, false, null)

    @Test
    fun `attribute modifiers read as the change they make`() {
        assertEquals("scale x0.5", AttributeLedger.describe(modifier("scale", -0.5, "add_multiplied_total")))
        assertEquals("gravity x2", AttributeLedger.describe(modifier("gravity", 1.0, "add_multiplied_total")))
        assertEquals("max_health +4", AttributeLedger.describe(modifier("max_health", 4.0, "add_value")))
        assertEquals("luck -1.25", AttributeLedger.describe(modifier("luck", -1.25, "add_value")))
        assertEquals("movement_speed +50% of base", AttributeLedger.describe(modifier("movement_speed", 0.5, "add_multiplied_base")))
    }

    /**
     * A stake or what is owed reads under its purpose, with no deadline (M4-LEFTOVERS: `Bartok 2x minecraft:emerald
     * until 21:40` told the Director neither what the bet was on nor that nothing ended at 21:40).
     */
    @Test
    fun `a stake and what is owed read under their purpose, with no deadline`() {
        val now = 1_000_000L
        val mimosa = UUID.randomUUID()
        val names = { uuid: UUID -> if (uuid == mimosa) "Mimosa" else null }
        fun entry(purpose: LedgerPurpose, what: String, returnAt: Long, reason: String?, canon: Long? = null, owedTo: UUID? = null) =
            Confiscation(812, emptyList(), what, now - 60_000, returnAt, reason, null, purpose, canon, owedTo)
        val week = now + 7 * 86_400_000L

        assertEquals(
            ActiveEffect("stake", "2x minecraft:emerald staked on C-50: a bet that Mimosa wins", null, harmful = false),
            Escrow.active(entry(LedgerPurpose.STAKE, "2x minecraft:emerald", week, "a bet that Mimosa wins", canon = 50), now, names),
        )
        assertEquals(
            ActiveEffect("stake", "2x minecraft:emerald staked on C-50 and lost: goes to Mimosa once both are online", null, harmful = false),
            Escrow.active(entry(LedgerPurpose.STAKE, "2x minecraft:emerald", week, "a bet that Bartok wins", canon = 50, owedTo = mimosa), now, names),
        )
        assertEquals(
            "2x minecraft:emerald staked on C-50 and lost: goes to its winner once both are online",
            Escrow.active(entry(LedgerPurpose.STAKE, "2x minecraft:emerald", week, "a bet that Bartok wins", canon = 50, owedTo = UUID.randomUUID()), now, names).what,
            "a winner offline is not named",
        )
        assertEquals(
            "2x minecraft:emerald staked on C-50 and settled (back to them once their inventory has room)",
            Escrow.active(entry(LedgerPurpose.STAKE, "2x minecraft:emerald", now, "a bet that Mimosa wins", canon = 50), now, names).what,
        )
        assertEquals(
            ActiveEffect(
                "owed", "1x minecraft:golden_apple owed to them (given once their inventory has room): the reward of the challenge C-50", null, harmful = false,
            ),
            Escrow.active(entry(LedgerPurpose.OWED, "1x minecraft:golden_apple", now - 60_000, "the reward of the challenge C-50"), now, names),
        )
        assertEquals(
            ActiveEffect("confiscation", "3x minecraft:diamond", now + 300_000, harmful = true),
            Escrow.active(entry(LedgerPurpose.CONFISCATION, "3x minecraft:diamond", now + 300_000, "greed"), now, names),
            "a confiscation, harmful, until it comes back",
        )
    }

    @Test
    fun `effects read with their level`() {
        assertEquals("slowness II", EffectLedger.describe(LedgerEffect("minecraft:slowness", 2, 0, true, null)))
        assertEquals("speed I", EffectLedger.describe(LedgerEffect("minecraft:speed", 1, 0, false, null)))
        assertEquals("levitation 12", EffectLedger.describe(LedgerEffect("minecraft:levitation", 12, 0, true, null)))
    }
}
