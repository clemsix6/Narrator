package io.github.clemsix6.narrator2.guard

import org.junit.jupiter.api.Test
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

    @Test
    fun `effects read with their level`() {
        assertEquals("slowness II", EffectLedger.describe(LedgerEffect("minecraft:slowness", 2, 0, true, null)))
        assertEquals("speed I", EffectLedger.describe(LedgerEffect("minecraft:speed", 1, 0, false, null)))
        assertEquals("levitation 12", EffectLedger.describe(LedgerEffect("minecraft:levitation", 12, 0, true, null)))
    }
}
